package com.example.data.contract

import android.content.ContentResolver
import android.net.Uri
import com.example.data.model.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

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
            val connection = openConnection("$base/v1/sources/upload", config.token, "POST").apply {
                setRequestProperty("Content-Type", "video/mp4")
                doOutput = true
            }
            try {
                val total = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
                val input = contentResolver.openInputStream(sourceUri) ?: error("تعذر فتح ملف الفيديو")
                var sent = 0L
                input.use { source ->
                    connection.outputStream.use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            sent += count
                            onProgress(UploadProgress(sent, total))
                        }
                    }
                }
                readJson(connection).toUploadResource().also {
                    require(it.id.isNotBlank() && it.source.isNotBlank()) { "استجابة الرفع غير مكتملة" }
                }
            } finally {
                connection.disconnect()
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
    }
}

class ApiContractException(val apiError: ApiError) : IOException(apiError.message)

