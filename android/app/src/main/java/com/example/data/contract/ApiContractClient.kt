package com.example.data.contract

import android.content.ContentResolver
import android.net.Uri
import com.example.data.model.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class ApiContractClient(private val contentResolver: ContentResolver) {
    data class UploadProgress(val sentBytes: Long, val totalBytes: Long)

    suspend fun health(config: GatewayConfig): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val json = requestJson(config, "/health", "GET")
            require(json.optBoolean("ok", false)) { json.optString("status", "Gateway غير جاهز") }
            json.optString("status", "ok")
        }
    }

    suspend fun upload(
        config: GatewayConfig,
        sourceUri: Uri,
        onProgress: suspend (UploadProgress) -> Unit
    ): Result<UploadResource> = withContext(Dispatchers.IO) {
        runCatching {
            val base = validateBaseUrl(config.baseUrl)
            val total = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
            require(total > 0L) { "تعذر معرفة حجم ملف الفيديو" }
            val checksum = sha256(sourceUri, total)
            val session = requestJson(
                config,
                "/v1/sources/uploads",
                "POST",
                JSONObject().put("filename", sourceUri.lastPathSegment?.substringAfterLast('/') ?: "source.mp4")
                    .put("bytes", total)
                    .put("sha256", checksum)
            )
            val uploadId = session.optString("id").takeIf { it.isNotBlank() } ?: error("استجابة جلسة الرفع غير مكتملة")
            var offset = session.optLong("offset", 0L).coerceIn(0L, total)
            val chunkBytes = session.optLong("chunk_bytes", DEFAULT_UPLOAD_CHUNK_BYTES.toLong()).coerceIn(1L, MAX_UPLOAD_CHUNK_BYTES.toLong())
            if (session.optString("status").equals("done", true) || session.optString("status").equals("completed", true)) {
                onProgress(UploadProgress(total, total))
                return@runCatching session.toUploadResource()
            }
            while (offset < total) {
                val length = minOf(chunkBytes, total - offset)
                val response = uploadChunk(config, uploadId, sourceUri, total, offset, length)
                val nextOffset = response.optLong("offset", offset + length)
                require(nextOffset == offset + length) { "Gateway أعاد offset غير متوقع" }
                offset = nextOffset
                onProgress(UploadProgress(offset, total))
            }
            val completed = requestJson(config, "/v1/sources/uploads/${Uri.encode(uploadId)}/complete", "POST", JSONObject())
            onProgress(UploadProgress(total, total))
            completed.toUploadResource()
        }
    }

    private fun sha256(sourceUri: Uri, total: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = contentResolver.openInputStream(sourceUri) ?: throw IOException("تعذر فتح ملف الفيديو")
        var read = 0L
        input.use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
            }
        }
        require(read == total) { "تغير حجم ملف الفيديو أثناء الرفع" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uploadChunk(config: GatewayConfig, uploadId: String, sourceUri: Uri, total: Long, offset: Long, length: Long): JSONObject {
        val base = validateBaseUrl(config.baseUrl)
        val connection = openConnection("$base/v1/sources/uploads/${Uri.encode(uploadId)}", config.token, "PUT").apply {
            doOutput = true
            setRequestProperty("Content-Type", contentResolver.getType(sourceUri) ?: "video/mp4")
            setRequestProperty("X-Upload-Offset", offset.toString())
            setRequestProperty("Content-Range", "bytes $offset-${offset + length - 1}/$total")
            setFixedLengthStreamingMode(length)
        }
        return try {
            val input = contentResolver.openInputStream(sourceUri) ?: throw IOException("تعذر فتح ملف الفيديو")
            input.use { source ->
                skipFully(source, offset)
                connection.outputStream.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = length
                    while (remaining > 0L) {
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("انتهى الملف قبل اكتمال الجزء")
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("تعذر الوصول إلى موضع الاستئناف")
                remaining -= count
            }
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
                require(artifact.mediaUrl.startsWith("http")) { "رابط النتيجة غير صالح" }
                val connection = openConnection(artifact.mediaUrl, config.token, "GET")
                try {
                    connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                    require(destination.isFile && destination.length() > 0) { "ملف النتيجة فارغ" }
                    destination
                } finally {
                    connection.disconnect()
                }
            }
        }

    private suspend fun control(config: GatewayConfig, jobId: String, action: String): Result<JobResource> =
        withContext(Dispatchers.IO) {
            runCatching { requestJson(config, "/v1/processing/jobs/${Uri.encode(jobId)}/$action", "POST", JSONObject()).toJobResource(jobId) }
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
            val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty()
            val safeMessage = detail.ifBlank { text }.take(300).ifBlank { connection.responseMessage.orEmpty() }
            throw ApiContractException(
                ApiError(
                    code = codeFor(status, safeMessage),
                    message = safeMessage,
                    retryable = status == 408 || status == 425 || status == 429 || status >= 500
                )
            )
        }
        return JSONObject(text)
    }

    private fun codeFor(status: Int, message: String): String = when {
        message.contains(":") -> message.substringBefore(":").takeIf { it.matches(Regex("[A-Z0-9_]+")) } ?: "HTTP_$status"
        else -> "HTTP_$status"
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val DEFAULT_UPLOAD_CHUNK_BYTES = 16 * 1024 * 1024
        private const val MAX_UPLOAD_CHUNK_BYTES = 64 * 1024 * 1024
    }
}

class ApiContractException(val apiError: ApiError) : IOException(apiError.message)
