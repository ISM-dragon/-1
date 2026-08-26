package com.example.data.contract

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlin.math.min

class ApiContractClient(private val contentResolver: ContentResolver) {
    data class UploadProgress(val sentBytes: Long, val totalBytes: Long)

    suspend fun health(config: GatewayConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = requestJson(config, "/health", "GET")
            require(json.optBoolean("ok", false)) { json.optString("status", "Gateway غير جاهز") }
            json.optString("status", "ok")
        }
    }

    /**
     * Uploads through the Gateway's resumable resource API. The server deduplicates
     * sessions by SHA-256 and returns the persisted offset, so a WorkManager retry
     * continues from the last accepted chunk instead of restarting the file.
     */
    suspend fun upload(
        config: GatewayConfig,
        sourceUri: Uri,
        onProgress: suspend (UploadProgress) -> Unit
    ): Result<UploadResource> = withContext(Dispatchers.IO) {
        runCatching {
            val base = validateBaseUrl(config.baseUrl)
            val metadata = sourceMetadata(sourceUri)
            val session = createUploadSession(config, metadata)
            val source = session.source
            if (!source.isNullOrBlank() && session.status in COMPLETED_UPLOAD_STATES) {
                onProgress(UploadProgress(metadata.bytes, metadata.bytes))
                return@runCatching UploadResource(
                    id = session.id,
                    source = source,
                    filename = metadata.filename,
                    bytes = metadata.bytes
                )
            }

            var offset = session.offset.coerceIn(0L, metadata.bytes)
            val chunkBytes = session.chunkBytes.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
            while (offset < metadata.bytes) {
                val endExclusive = min(metadata.bytes, offset + chunkBytes)
                val chunk = readRange(sourceUri, offset, endExclusive - offset)
                val response = putUploadChunk(
                    base = base,
                    token = config.token,
                    uploadId = session.id,
                    start = offset,
                    endExclusive = endExclusive,
                    total = metadata.bytes,
                    chunk = chunk
                )
                val acknowledged = response.optLong("offset", endExclusive)
                require(acknowledged in (offset + 1)..endExclusive) {
                    "Gateway أعاد offset غير صالح للرفع"
                }
                offset = acknowledged
                onProgress(UploadProgress(offset, metadata.bytes))
            }

            val completed = requestJson(
                config,
                "/v1/sources/uploads/${Uri.encode(session.id)}/complete",
                "POST",
                JSONObject()
            ).toUploadResource()
            require(completed.id.isNotBlank() && completed.source.isNotBlank()) {
                "استجابة إكمال الرفع غير مكتملة"
            }
            onProgress(UploadProgress(metadata.bytes, metadata.bytes))
            completed.copy(filename = completed.filename.ifBlank { metadata.filename }, bytes = metadata.bytes)
        }
    }

    suspend fun createJob(config: GatewayConfig, request: ProcessingRequest): Result<JobResource> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = JSONObject()
                    .put("source", request.source)
                    .put("llm", request.llm)
                    .put("captions", request.captions)
                    .put("mode", request.mode)
                    .put("idempotency_key", request.idempotencyKey)
                requestJson(config, "/v1/processing/jobs", "POST", body).toJobResource()
            }
        }

    suspend fun getJob(config: GatewayConfig, jobId: String): Result<JobResource> =
        withContext(Dispatchers.IO) {
            runCatching { requestJson(config, "/v1/processing/jobs/${Uri.encode(jobId)}", "GET").toJobResource(jobId) }
        }

    suspend fun cancel(config: GatewayConfig, jobId: String): Result<JobResource> = control(config, jobId, "cancel")
    suspend fun retry(config: GatewayConfig, jobId: String): Result<JobResource> = control(config, jobId, "retry")
    suspend fun resume(config: GatewayConfig, jobId: String): Result<JobResource> = control(config, jobId, "resume")

    suspend fun download(config: GatewayConfig, artifact: ClipArtifact, destination: File): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mediaUrl = validateMediaUrl(config.baseUrl, artifact.mediaUrl)
                val connection = openConnection(mediaUrl, config.token, "GET")
                try {
                    destination.parentFile?.mkdirs()
                    val digest = MessageDigest.getInstance("SHA-256")
                    connection.inputStream.use { input ->
                        destination.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(destination.isFile && destination.length() > 0) { "ملف النتيجة فارغ" }
                    artifact.sha256?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let { expected ->
                        val actual = digest.digest().hex()
                        if (actual != expected) {
                            destination.delete()
                            error("فشل التحقق من سلامة ملف النتيجة")
                        }
                    }
                    destination
                } finally {
                    connection.disconnect()
                }
            }
        }

    private suspend fun control(config: GatewayConfig, jobId: String, action: String): Result<JobResource> =
        withContext(Dispatchers.IO) {
            runCatching {
                requestJson(
                    config,
                    "/v1/processing/jobs/${Uri.encode(jobId)}/$action",
                    "POST",
                    JSONObject()
                ).toJobResource(jobId)
            }
        }

    private fun createUploadSession(config: GatewayConfig, metadata: SourceMetadata): UploadSession {
        val body = JSONObject()
            .put("filename", metadata.filename)
            .put("bytes", metadata.bytes)
            .put("sha256", metadata.sha256)
        val json = requestJson(config, "/v1/sources/uploads", "POST", body)
        return UploadSession(
            id = json.optString("id").takeIf { it.isNotBlank() } ?: error("Gateway لم يُرجع معرف جلسة الرفع"),
            status = json.optString("status", "uploading").lowercase(),
            offset = json.optLong("offset", 0L),
            chunkBytes = json.optInt("chunk_bytes", DEFAULT_CHUNK_BYTES),
            source = json.optString("source").takeIf { it.isNotBlank() }
        )
    }

    private fun putUploadChunk(
        base: String,
        token: String,
        uploadId: String,
        start: Long,
        endExclusive: Long,
        total: Long,
        chunk: ByteArray
    ): JSONObject {
        val end = endExclusive - 1
        val connection = openConnection("$base/v1/sources/uploads/${Uri.encode(uploadId)}", token, "PUT").apply {
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Content-Range", "bytes $start-$end/$total")
            setRequestProperty("X-Upload-Offset", start.toString())
            setFixedLengthStreamingMode(chunk.size)
            doOutput = true
        }
        return try {
            connection.outputStream.use { it.write(chunk) }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun sourceMetadata(sourceUri: Uri): SourceMetadata {
        val filename = queryDisplayName(sourceUri)
            ?.takeIf { it.isNotBlank() }
            ?: sourceUri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "source.mp4"
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val input = contentResolver.openInputStream(sourceUri) ?: error("تعذر فتح ملف الفيديو")
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
        require(bytes > 0L) { "ملف الفيديو فارغ" }
        return SourceMetadata(filename = safeVideoFilename(filename), bytes = bytes, sha256 = digest.digest().hex())
    }

    private fun queryDisplayName(sourceUri: Uri): String? {
        if (sourceUri.scheme != "content") return null
        val cursor: Cursor? = contentResolver.query(
            sourceUri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )
        return cursor?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    private fun readRange(sourceUri: Uri, start: Long, length: Long): ByteArray {
        require(length in 1L..Int.MAX_VALUE) { "حجم chunk غير صالح" }
        val result = ByteArray(length.toInt())
        val input = contentResolver.openInputStream(sourceUri) ?: error("تعذر إعادة فتح ملف الفيديو")
        input.use { source ->
            var remaining = start
            while (remaining > 0) {
                val skipped = source.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else if (source.read() >= 0) {
                    remaining -= 1
                } else {
                    error("تعذر الوصول إلى موضع استئناف الرفع")
                }
            }
            var filled = 0
            while (filled < result.size) {
                val count = source.read(result, filled, result.size - filled)
                if (count < 0) break
                if (count > 0) filled += count
            }
            require(filled == result.size) { "ملف الفيديو تغير أثناء الرفع" }
        }
        return result
    }

    private fun requestJson(config: GatewayConfig, path: String, method: String, body: JSONObject? = null): JSONObject {
        val connection = openConnection("${validateBaseUrl(config.baseUrl)}$path", config.token, method).apply {
            if (body != null) {
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
        }
        return try {
            body?.let { connection.outputStream.use { stream -> stream.write(it.toString().toByteArray(Charsets.UTF_8)) } }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, token: String, method: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw ApiContractException(parseApiError(status, text, connection.responseMessage.orEmpty()))
        }
        return JSONObject(text)
    }

    private fun parseApiError(status: Int, body: String, responseMessage: String): ApiError {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val detail = json?.opt("detail")
        var code: String? = null
        var message: String? = null
        when (detail) {
            is JSONObject -> {
                code = detail.optString("code").takeIf { it.isNotBlank() }
                message = detail.optString("message").takeIf { it.isNotBlank() }
            }
            is String -> {
                message = detail.takeIf { it.isNotBlank() }
                code = message?.let { codeFor(status, it) }?.takeIf { it != "HTTP_$status" }
            }
        }
        val errors = json?.optJSONArray("errors")
        val firstError = errors?.optJSONObject(0)
        if (firstError != null) {
            code = code ?: firstError.optString("code").takeIf { it.isNotBlank() }
            message = message ?: firstError.optString("message").takeIf { it.isNotBlank() }
        }
        val safeMessage = (message ?: body).take(300).ifBlank { responseMessage }
        return ApiError(
            code = code ?: codeFor(status, safeMessage),
            message = safeMessage,
            requestId = json?.optString("request_id")?.takeIf { it.isNotBlank() },
            retryable = status == 408 || status == 425 || status == 429 || status >= 500
        )
    }

    private fun codeFor(status: Int, message: String): String = when {
        message.contains(":") -> message.substringBefore(":").takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: "HTTP_$status"
        else -> "HTTP_$status"
    }

    private fun validateMediaUrl(baseUrl: String, mediaUrl: String): String {
        val base = URI(validateBaseUrl(baseUrl))
        val media = URI(mediaUrl)
        val basePort = if (base.port == -1) base.toURL().defaultPort else base.port
        val mediaPort = if (media.port == -1) media.toURL().defaultPort else media.port
        require(media.scheme?.lowercase() == base.scheme?.lowercase() && media.host.equals(base.host, ignoreCase = true) && mediaPort == basePort) {
            "رابط النتيجة يجب أن ينتمي إلى Gateway المضبوط"
        }
        return mediaUrl
    }

    private fun validateBaseUrl(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        require(normalized.isNotBlank()) { "عنوان Gateway مطلوب" }
        val uri = URI(normalized)
        val host = uri.host.orEmpty().lowercase()
        val local = host == "localhost" || host == "127.0.0.1" || host.startsWith("10.") ||
            host.startsWith("192.168.") || host.startsWith("172.16.")
        require(uri.scheme?.lowercase() == "https" || (uri.scheme?.lowercase() == "http" && local)) {
            "استخدم HTTPS خارج الشبكة المحلية"
        }
        require(!uri.host.isNullOrBlank()) { "عنوان Gateway غير صالح" }
        return normalized
    }

    private data class SourceMetadata(val filename: String, val bytes: Long, val sha256: String)

    private data class UploadSession(
        val id: String,
        val status: String,
        val offset: Long,
        val chunkBytes: Int,
        val source: String?
    )

    private fun safeVideoFilename(raw: String): String {
        val name = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val extension = name.substringAfterLast('.', "").lowercase()
        return if (extension in SUPPORTED_EXTENSIONS) name.take(180) else "source.mp4"
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DEFAULT_CHUNK_BYTES = 16 * 1024 * 1024
        private const val MIN_CHUNK_BYTES = 64 * 1024
        private const val MAX_CHUNK_BYTES = 64 * 1024 * 1024
        private val COMPLETED_UPLOAD_STATES = setOf("done", "completed")
        private val SUPPORTED_EXTENSIONS = setOf("mp4", "mov", "mkv", "webm")
    }
}

class ApiContractException(val apiError: ApiError) : IOException(apiError.message)
