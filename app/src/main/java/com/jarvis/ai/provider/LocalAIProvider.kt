package com.jarvis.ai.provider

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Loads GGUF models for on-device inference.
 *
 * Android content Uris (SAF picker, cloud-backed providers, some file managers) do not
 * always hand back a seekable / mmap-able file descriptor — llama.cpp's mmap loader
 * needs a real regular file on disk. Passing the raw fd straight to native code (or the
 * `/proc/self/fd/<n>` trick) works for plain local files but silently fails or crashes
 * for pipe-backed, virtual, or cloud-streamed documents.
 *
 * To be robust across every provider, we stream the selected model into the app's
 * private storage once, then hand llama.cpp a normal absolute path. Subsequent loads of
 * the same model (same source + size) reuse the cached copy instead of re-copying.
 */
class LocalAIProvider(private val context: Context) : AIProvider {
    interface TokenCallback {
        fun onTokenGenerated(token: String)
    }

    /** null on success; non-null (human readable) describes what went wrong. */
    @Volatile
    var lastError: String? = null
        private set

    private external fun nativeInitModel(path: String, contextSize: Int): Boolean
    private external fun nativeGenerateToken(prompt: String, callback: TokenCallback): Boolean
    private external fun nativeStop()
    private external fun nativeFree()

    @Volatile
    override var isInitialized: Boolean = false
        private set

    override var loadedModelUri: Uri? = null
        private set

    private var modelFile: File? = null

    override suspend fun initialize(uri: Uri, contextSize: Int): Boolean =
        initialize(uri, contextSize) {}

    /**
     * @param onProgress called on a background thread with 0..100 while the model is
     * being staged to local storage. Copying is skipped (progress jumps straight to 100)
     * when a valid cached copy already exists for this uri.
     */
    suspend fun initialize(uri: Uri, contextSize: Int, onProgress: (Int) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            unloadModel()
            lastError = null

            val localPath = try {
                resolveToLocalFile(uri, onProgress)
            } catch (e: IOException) {
                lastError = "Could not read model: ${e.message}"
                return@withContext false
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = "Unexpected error staging model: ${t.message}"
                return@withContext false
            }

            if (localPath == null) {
                if (lastError == null) lastError = "Unable to access selected file"
                return@withContext false
            }

            val ok = try {
                nativeInitModel(localPath.absolutePath, contextSize)
            } catch (t: Throwable) {
                lastError = "Native init crashed: ${t.message}"
                false
            }

            if (ok) {
                modelFile = localPath
                loadedModelUri = uri
                isInitialized = true
                true
            } else {
                if (lastError == null) lastError = "llama.cpp rejected the model file (corrupt or unsupported GGUF?)"
                false
            }
        }

    /**
     * Ensures [uri] is available as a real, seekable file under the app's private storage
     * and returns that file. Reuses a previously staged copy when the source size still
     * matches, so re-selecting the same model doesn't re-copy multi-GB files every time.
     */
    private suspend fun resolveToLocalFile(uri: Uri, onProgress: (Int) -> Unit): File? {
        // Fast path: uri already points at a plain, locally readable file (e.g. picked
        // from a "raw" file manager) — no need to duplicate it.
        if (uri.scheme == "file") {
            val direct = uri.path?.let { File(it) }
            if (direct != null && direct.isFile && direct.canRead()) return direct
        }

        val resolver = context.contentResolver
        val (displayName, expectedSize) = queryMeta(uri)
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val cacheKey = cacheKeyFor(uri, displayName)
        val target = File(modelsDir, "$cacheKey.gguf")
        val marker = File(modelsDir, "$cacheKey.size")

        // Reuse an existing staged copy if its recorded source size still matches.
        if (target.isFile && marker.isFile) {
            val cachedSize = marker.readText().trim().toLongOrNull()
            if (cachedSize != null && (expectedSize < 0 || cachedSize == expectedSize) && target.length() == cachedSize) {
                onProgress(100)
                return target
            }
        }

        if (expectedSize > 0) {
            val free = StatFs(modelsDir.path).availableBytes
            // leave a little headroom rather than filling the disk to the last byte
            if (free < expectedSize + 64L * 1024 * 1024) {
                throw IOException("Not enough free storage (${expectedSize / (1024 * 1024)} MB needed)")
            }
        }

        val tmp = File(modelsDir, "$cacheKey.part")
        resolver.openInputStream(uri)?.use { input ->
            Channels.newChannel(input).use { src ->
                java.io.FileOutputStream(tmp).use { fos ->
                    fos.channel.use { dst ->
                        copyWithProgress(src, dst, expectedSize, onProgress)
                    }
                }
            }
        } ?: throw IOException("contentResolver could not open the selected file")

        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        marker.writeText(target.length().toString())
        onProgress(100)
        return target
    }

    private suspend fun copyWithProgress(
        src: java.nio.channels.ReadableByteChannel,
        dst: FileChannel,
        expectedSize: Long,
        onProgress: (Int) -> Unit
    ) {
        val chunk = 8L * 1024 * 1024
        var transferred = 0L
        var lastReported = -1
        while (true) {
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) {
                throw IOException("Copy cancelled")
            }
            val n = dst.transferFrom(src, transferred, chunk)
            if (n <= 0) break
            transferred += n
            if (expectedSize > 0) {
                val pct = ((transferred * 100) / expectedSize).toInt().coerceIn(0, 99)
                if (pct != lastReported) {
                    onProgress(pct)
                    lastReported = pct
                }
            }
        }
    }

    private fun queryMeta(uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (_: Exception) {
            // Some providers don't support querying metadata at all — fall back to uri hash.
        }
        return name to size
    }

    private fun cacheKeyFor(uri: Uri, displayName: String?): String {
        val basis = displayName ?: uri.toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        val hash = digest.joinToString("") { "%02x".format(it) }.take(16)
        val safeName = basis.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(40)
        return "${hash}_$safeName"
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
        modelFile = null
        // Note: the staged copy under filesDir/models is deliberately kept on disk so
        // reselecting the same model next time skips the (potentially multi-GB) copy.
    }

    /** Deletes every model staged in local storage, freeing the space they used. */
    fun clearCachedModels() {
        File(context.filesDir, "models").listFiles()?.forEach { it.delete() }
    }

    companion object {
        init {
            System.loadLibrary("jarvis_native")
        }
    }
}
