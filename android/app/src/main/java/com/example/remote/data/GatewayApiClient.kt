package com.example.remote.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.example.remote.model.GatewayConfig
import com.example.remote.model.GatewayError
import com.example.remote.model.GatewayHealth
import com.example.remote.model.GatewayJob
import com.example.remote.model.UploadedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.math.min

class GatewayApiClient(private val contentResolver: ContentResolver) {
    suspend fun checkConnection(config: GatewayConfig): Result<GatewayHealth> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val health = requestJson("$baseUrl/health", config.token, "GET")
            val capabilities = requestJson("$baseUrl/v1/processing/capabilities", config.token, "GET")
            val session = requestJson("$baseUrl/v1/auth/session", config.token, "GET")
            val pipelineReady = capabilities.optBoolean("pipeline", false)
            val ffmpegReady = capabilities.optBoolean("ffmpeg", false)
            val runtimeReady = capabilities.optBoolean("runtime_ready", pipelineReady && ffmpegReady)
            GatewayHealth(
                ok = health.optBoolean("ok", health.optString("status") == "ok"),
                authenticated = session.optBoolean("authenticated", true),
                gatewayReady = capabilities.optBoolean("gateway", true),
                pipelineReady = pipelineReady,
                ffmpegReady = ffmpegReady,
                runtimeReady = runtimeReady,
                message = if (pipelineReady && ffmpegReady && runtimeReady) {
                    "Gateway متصل وجاهز لاستقبال الوظائف."
                } else {
                    "Gateway متصل، لكن بعض القدرات غير جاهزة."
                }
            )
        }
    }

    /**
     * Uploads through the Gateway resumable API. Re-running this method with the
     * same source hash reuses an incomplete server-side session and continues at
     * its persisted offset, which makes WorkManager retries safe after a network
     * interruption.
     */
    suspend fun uploadVideo(
        config: GatewayConfig,
        sourceUri: Uri,
        onProgress: (Int) -> Unit
    ): Result<UploadedSource> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val prepared = prepareSource(sourceUri)
            try {
                val init = requestJson(
                    "$baseUrl/v1/sources/uploads",
                    config.token,
                    "POST",
                    JSONObject()
                        .put("filename", prepared.filename)
                        .put("bytes", prepared.bytes)
                        .put("sha256", prepared.sha256)
                )
                var offset = init.optLong("offset", init.optLong("received_bytes", 0L)).coerceIn(0L, prepared.bytes)
                val uploadId = init.optString("id").requireNonBlank("Gateway لم يُرجع معرّف جلسة الرفع")
                val initialStatus = init.optString("status").lowercase()
                if (initialStatus == "done" || initialStatus == "completed") {
                    onProgress(100)
                    return@runCatching toUploadedSource(init, prepared.bytes)
                }

                val chunkBytes = init.optLong("chunk_bytes", DEFAULT_CHUNK_BYTES)
                    .coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
                while (offset < prepared.bytes) {
                    val endExclusive = min(prepared.bytes, offset + chunkBytes)
                    var attempts = 0
                    while (true) {
                        try {
                            val response = prepared.open().use { input ->
                                skipFully(input, offset)
                                writeChunk(
                                    baseUrl = baseUrl,
                                    token = config.token,
                                    uploadId = uploadId,
                                    input = input,
                                    start = offset,
                                    endExclusive = endExclusive,
                                    total = prepared.bytes
                                )
                            }
                            val nextOffset = response.optLong(
                                "offset",
                                response.optLong("received_bytes", endExclusive)
                            )
                            require(nextOffset in (offset + 1)..endExclusive) {
                                "استجابة الرفع أعادت offset غير صالح"
                            }
                            offset = nextOffset
                            reportProgress(offset, prepared.bytes, onProgress)
                            break
                        } catch (error: IOException) {
                            attempts += 1
                            if (attempts >= MAX_CHUNK_ATTEMPTS) throw error
                            val status = requestJson(
                                "$baseUrl/v1/sources/uploads/$uploadId",
                                config.token,
                                "GET"
                            )
                            offset = status.optLong("offset", status.optLong("received_bytes", offset))
                                .coerceIn(0L, prepared.bytes)
                        }
                    }
                }

                val completed = requestJson(
                    "$baseUrl/v1/sources/uploads/$uploadId/complete",
                    config.token,
                    "POST",
                    JSONObject()
                )
                onProgress(100)
                toUploadedSource(completed, prepared.bytes)
            } finally {
                prepared.cleanup()
            }
        }
    }

    suspend fun createJob(
        config: GatewayConfig,
        sourceUrl: String,
        idempotencyKey: String,
        captions: String = "classic",
        mode: String = "balanced"
    ): Result<GatewayJob> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val body = JSONObject()
                .put("source", sourceUrl)
                .put("llm", config.llm)
                .put("captions", captions)
                .put("mode", mode)
                .put("idempotency_key", idempotencyKey)
            val json = requestJson("$baseUrl/v1/processing/jobs", config.token, "POST", body)
            GatewayJob.fromJson(json, json.optString("job_id", json.optString("id")))
        }
    }

    suspend fun getJob(config: GatewayConfig, jobId: String): Result<GatewayJob> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val json = requestJson("$baseUrl/v1/processing/jobs/${Uri.encode(jobId)}", config.token, "GET")
            GatewayJob.fromJson(json, jobId)
        }
    }

    suspend fun cancel(config: GatewayConfig, jobId: String): Result<GatewayJob> = control(config, jobId, "cancel")
    suspend fun retry(config: GatewayConfig, jobId: String): Result<GatewayJob> = control(config, jobId, "retry")
    suspend fun resume(config: GatewayConfig, jobId: String): Result<GatewayJob> = control(config, jobId, "resume")

    suspend fun downloadMedia(
        config: GatewayConfig,
        mediaUrl: String,
        destination: File
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            validateMediaUrl(config, mediaUrl)
            val connection = openConnection(mediaUrl, config.token, "GET")
            try {
                val parent = destination.parentFile ?: throw IOException("مجلد النتائج غير صالح")
                parent.mkdirs()
                connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                if (!destination.isFile || destination.length() == 0L) throw IOException("النتيجة التي أعادها Gateway فارغة")
                destination
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun control(config: GatewayConfig, jobId: String, action: String): Result<GatewayJob> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val json = requestJson(
                "$baseUrl/v1/processing/jobs/${Uri.encode(jobId)}/$action",
                config.token,
                "POST",
                JSONObject()
            )
            GatewayJob.fromJson(json, jobId)
        }
    }

    private fun writeChunk(
        baseUrl: String,
        token: String,
        uploadId: String,
        input: InputStream,
        start: Long,
        endExclusive: Long,
        total: Long
    ): JSONObject {
        val length = endExclusive - start
        val connection = openConnection("$baseUrl/v1/sources/uploads/$uploadId", token, "PUT").apply {
            doOutput = true
            setFixedLengthStreamingMode(length)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Content-Range", "bytes $start-${endExclusive - 1}/$total")
            setRequestProperty("X-Upload-Offset", start.toString())
        }
        return try {
            connection.outputStream.use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buffer, 0, min(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IOException("انتهى مصدر الفيديو قبل اكتمال chunk")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun requestJson(
        url: String,
        token: String,
        method: String,
        body: JSONObject? = null
    ): JSONObject {
        val connection = openConnection(url, token, method).apply {
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()
            ?.use { it.readText() }
            .orEmpty()
        if (status !in 200..299) throw parseError(status, body)
        return JSONObject(body)
    }

    private fun parseError(status: Int, body: String): GatewayError {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val error = json?.optJSONObject("error")
        val code = error?.optString("code")?.takeIf { it.isNotBlank() }
            ?: json?.optString("error_code")?.takeIf { it.isNotBlank() }
            ?: body.substringBefore(":").takeIf { it.matches(Regex("[A-Z0-9_]+")) }
            ?: "HTTP_$status"
        val message = error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("detail")?.takeIf { it.isNotBlank() }
            ?: body.take(240).ifBlank { "فشل طلب Gateway (HTTP $status)" }
        val retryable = error?.optBoolean("retryable", status == 408 || status == 429 || status >= 500)
            ?: (status == 408 || status == 429 || status >= 500)
        return GatewayError(code, message, retryable, status)
    }

    private fun openConnection(url: String, token: String, method: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

    private fun validateMediaUrl(config: GatewayConfig, mediaUrl: String) {
        val media = URI(mediaUrl)
        val base = URI(validateBaseUrl(config.baseUrl))
        require(media.scheme == base.scheme && media.host == base.host) { "رابط النتيجة لا ينتمي إلى Gateway المضبوط" }
    }

    private fun validateBaseUrl(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        require(normalized.isNotBlank()) { "أدخل عنوان Gateway أولاً" }
        val uri = URI(normalized)
        val host = uri.host.orEmpty().lowercase()
        val local = host == "localhost" || host == "127.0.0.1" || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.16.")
        require(uri.scheme?.lowercase() == "https" || local) { "يجب استخدام HTTPS خارج الشبكة المحلية" }
        require(!uri.host.isNullOrBlank()) { "عنوان Gateway غير صالح" }
        return normalized
    }

    private data class PreparedSource(
        val filename: String,
        val bytes: Long,
        val sha256: String,
        val open: () -> InputStream,
        val cleanup: () -> Unit
    )

    private fun prepareSource(uri: Uri): PreparedSource {
        var stagedFile: File? = null
        var bytes = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (bytes < 0L) {
            stagedFile = File.createTempFile("publikclip-upload-", ".media")
            contentResolver.openInputStream(uri)?.use { input -> stagedFile!!.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("تعذر فتح الفيديو المختار")
            bytes = stagedFile!!.length()
        }
        require(bytes > 0L) { "ملف الفيديو فارغ" }
        val digest = MessageDigest.getInstance("SHA-256")
        openSource(uri, stagedFile).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return PreparedSource(
            filename = displayName(uri),
            bytes = bytes,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            open = { openSource(uri, stagedFile) },
            cleanup = { stagedFile?.delete() }
        )
    }

    private fun openSource(uri: Uri, stagedFile: File?): InputStream =
        stagedFile?.inputStream() ?: contentResolver.openInputStream(uri) ?: throw IOException("تعذر فتح الفيديو المختار")

    private fun displayName(uri: Uri): String {
        val fromProvider = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        val candidate = fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "source.mp4"
        return candidate.substringAfterLast('/').ifBlank { "source.mp4" }
    }

    private fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining -= 1L
            } else {
                throw IOException("تعذر الوصول إلى offset الرفع المطلوب")
            }
        }
    }

    private fun toUploadedSource(json: JSONObject, fallbackBytes: Long): UploadedSource = UploadedSource(
        id = json.optString("id").requireNonBlank("Gateway لم يُرجع معرّف المصدر"),
        sourceUrl = json.optString("source").requireNonBlank("Gateway لم يُرجع رابط المصدر"),
        filename = json.optString("filename", "source.mp4"),
        bytes = json.optLong("bytes", fallbackBytes)
    )

    private fun reportProgress(offset: Long, total: Long, onProgress: (Int) -> Unit) {
        onProgress(if (total > 0L) ((offset * 100L) / total).toInt().coerceIn(0, 100) else 0)
    }

    private fun String.requireNonBlank(message: String): String = takeIf { it.isNotBlank() } ?: error(message)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DEFAULT_CHUNK_BYTES = 16L * 1024L * 1024L
        private const val MIN_CHUNK_BYTES = 1L
        private const val MAX_CHUNK_BYTES = 64L * 1024L * 1024L
        private const val MAX_CHUNK_ATTEMPTS = 3
    }
}
