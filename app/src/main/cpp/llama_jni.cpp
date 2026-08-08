#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "JARVIS_NATIVE"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
struct NativeState {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * sampler = nullptr;
    std::atomic<bool> stop{false};
    std::mutex mutex;
};

NativeState g_state;
std::once_flag g_backend_once;

void ensureBackend() {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
}

void freeStateLocked() {
    if (g_state.sampler) {
        llama_sampler_free(g_state.sampler);
        g_state.sampler = nullptr;
    }
    if (g_state.ctx) {
        llama_free(g_state.ctx);
        g_state.ctx = nullptr;
    }
    if (g_state.model) {
        llama_model_free(g_state.model);
        g_state.model = nullptr;
    }
}

jclass findCallbackClass(JNIEnv * env, jobject callback) {
    return env->GetObjectClass(callback);
}

void emitToken(JNIEnv * env, jobject callback, jmethodID method, const std::string & token) {
    if (token.empty()) return;
    jstring value = env->NewStringUTF(token.c_str());
    env->CallVoidMethod(callback, method, value);
    env->DeleteLocalRef(value);
}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & prompt) {
    int32_t cap = static_cast<int32_t>(prompt.size() + 512);
    if (cap < 512) cap = 512;
    std::vector<llama_token> tokens(static_cast<size_t>(cap));
    int32_t n = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                               tokens.data(), cap, true, false);
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                           tokens.data(), static_cast<int32_t>(tokens.size()), true, false);
    }
    if (n < 0) return {};
    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

std::string tokenPiece(const llama_vocab * vocab, llama_token token) {
    char buf[256];
    int32_t n = llama_token_to_piece(vocab, token, buf, static_cast<int32_t>(sizeof(buf)), 0, false);
    if (n < 0) {
        std::vector<char> dynamic(static_cast<size_t>(-n));
        n = llama_token_to_piece(vocab, token, dynamic.data(), static_cast<int32_t>(dynamic.size()), 0, false);
        if (n > 0) return std::string(dynamic.data(), static_cast<size_t>(n));
        return {};
    }
    if (n > 0) return std::string(buf, static_cast<size_t>(n));
    return {};
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_ai_provider_LocalAIProvider_nativeInitModel(
        JNIEnv * env, jobject, jint fd, jint contextSize) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ensureBackend();
    freeStateLocked();
    g_state.stop.store(false);

    char path[64];
    std::snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);

    llama_model_params modelParams = llama_model_default_params();
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model * model = llama_model_load_from_file(path, modelParams);
    if (!model) {
        LOGE("Unable to load GGUF model from fd=%d", fd);
        return JNI_FALSE;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = static_cast<uint32_t>(contextSize);
    ctxParams.n_batch = 512;
    ctxParams.n_ubatch = 256;
    ctxParams.n_threads = 4;
    ctxParams.n_threads_batch = 4;
    ctxParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;

    llama_context * ctx = llama_init_from_model(model, ctxParams);
    if (!ctx) {
        llama_model_free(model);
        LOGE("Unable to create llama context");
        return JNI_FALSE;
    }

    auto samplerParams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(samplerParams);
    if (!sampler) {
        llama_free(ctx);
        llama_model_free(model);
        return JNI_FALSE;
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.70f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(1234));

    g_state.model = model;
    g_state.ctx = ctx;
    g_state.sampler = sampler;
    LOGI("JARVIS model initialized: ctx=%d", contextSize);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_ai_provider_LocalAIProvider_nativeGenerateToken(
        JNIEnv * env, jobject, jstring prompt, jobject callback) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.model || !g_state.ctx || !g_state.sampler || !callback) return JNI_FALSE;

    jclass callbackClass = findCallbackClass(env, callback);
    jmethodID method = env->GetMethodID(callbackClass, "onTokenGenerated", "(Ljava/lang/String;)V");
    if (!method) return JNI_FALSE;

    const char * promptChars = env->GetStringUTFChars(prompt, nullptr);
    std::string promptText = promptChars ? promptChars : "";
    if (promptChars) env->ReleaseStringUTFChars(prompt, promptChars);

    const llama_vocab * vocab = llama_model_get_vocab(g_state.model);
    std::vector<llama_token> tokens = tokenize(vocab, promptText);
    if (tokens.empty()) return JNI_FALSE;

    llama_sampler_reset(g_state.sampler);
    g_state.stop.store(false);

    llama_batch promptBatch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_state.ctx, promptBatch) < 0) {
        LOGE("Prompt decode failed");
        return JNI_FALSE;
    }

    constexpr int maxGenerated = 256;
    for (int i = 0; i < maxGenerated && !g_state.stop.load(); ++i) {
        llama_token id = llama_sampler_sample(g_state.sampler, g_state.ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        llama_sampler_accept(g_state.sampler, id);

        emitToken(env, callback, method, tokenPiece(vocab, id));

        llama_batch next = llama_batch_get_one(&id, 1);
        if (llama_decode(g_state.ctx, next) < 0) {
            LOGE("Token decode failed at token %d", i);
            break;
        }
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_ai_provider_LocalAIProvider_nativeStop(JNIEnv *, jobject) {
    g_state.stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_ai_provider_LocalAIProvider_nativeFree(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.stop.store(true);
    freeStateLocked();
}
