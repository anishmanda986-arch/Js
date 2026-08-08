package com.jarvis.ai.provider

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LocalAIProvider(private val context: Context) : AIProvider {
    interface TokenCallback {
        fun onTokenGenerated(token: String)
    }

    private external fun nativeInitModel(fd: Int, contextSize: Int): Boolean
    private external fun nativeGenerateToken(prompt: String, callback: TokenCallback): Boolean
    private external fun nativeStop()
    private external fun nativeFree()

    @Volatile
    override var isInitialized: Boolean = false
        private set

    override var loadedModelUri: Uri? = null
        private set

    private var modelFd: ParcelFileDescriptor? = null

    override suspend fun initialize(uri: Uri, contextSize: Int): Boolean = withContext(Dispatchers.IO) {
        unloadModel()
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext false
        val ok = try {
            nativeInitModel(descriptor.fd, contextSize)
        } catch (_: Throwable) {
            false
        }
        if (ok) {
            modelFd = descriptor
            loadedModelUri = uri
            isInitialized = true
            true
        } else {
            descriptor.close()
            false
        }
    }

    override fun generateResponse(prompt: String): Flow<String> = callbackFlow {
        if (!isInitialized) {
            close(IllegalStateException("Model not loaded"))
            return@callbackFlow
        }

        val producer = this

        val callback = object : TokenCallback {
            override fun onTokenGenerated(token: String) {
                trySend(token).isSuccess
            }
        }

        val job: Job = launch(Dispatchers.IO) {
            try {
                nativeGenerateToken(prompt, callback)
                producer.close()
            } catch (t: Throwable) {
                producer.close(t)
            }
        }

        awaitClose {
            nativeStop()
            job.cancel()
        }
    }

    override fun stopGeneration() {
        if (isInitialized) nativeStop()
    }

    override fun unloadModel() {
        if (isInitialized) {
            nativeStop()
            nativeFree()
        }
        isInitialized = false
        loadedModelUri = null
        modelFd?.close()
        modelFd = null
    }

    companion object {
        init {
            System.loadLibrary("jarvis_native")
        }
    }
}
