package com.example.remote.data

import android.content.ContentResolver
import android.net.Uri
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
import java.net.HttpURLConnection
import java.net.URI

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

    suspend fun uploadVideo(
        config: GatewayConfig,
        sourceUri: Uri,
        onProgress: (Int) -> Unit
    ): Result<UploadedSource> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val connection = openConnection("$baseUrl/v1/sources/upload", config.token, "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", contentResolver.getType(sourceUri) ?: "video/mp4")
                contentResolver.openAssetFileDescriptor(sourceUri, "r")?.length?.takeIf { it >= 0 }?.let {
                    setFixedLengthStreamingMode(it)
                } ?: run { setChunkedStreamingMode(64 * 1024) }
            }
            try {
                val total = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
                val input = contentResolver.openInputStream(sourceUri) ?: throw IOException("تعذر فتح الفيديو المختار")
                input.use { source ->
                    connection.outputStream.use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var copied = 0L
                        var lastReported = -1
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            val percent = if (total > 0) ((copied * 100) / total).toInt().coerceIn(0, 100) else 0
                            if (percent != lastReported) {
                                onProgress(percent)
                                lastReported = percent
                            }
                        }
                    }
                }
                val json = readJson(connection)
                UploadedSource(
                    id = json.optString("id").requireNonBlank("Gateway لم يُرجع معرّف الرفع"),
                    sourceUrl = json.optString("source").requireNonBlank("Gateway لم يُرجع رابط المصدر"),
                    filename = json.optString("filename", "source.mp4"),
                    bytes = json.optLong("bytes", 0L)
                )
            } finally {
                connection.disconnect()
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
                .put("llm", "gemini")
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
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

    private fun validateBaseUrl(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        require(normalized.isNotBlank()) { "أدخل عنوان Gateway أولاً" }
        val uri = URI(normalized)
        val host = uri.host.orEmpty().lowercase()
        val local = host == "localhost" || host == "127.0.0.1" || host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("172.16.")
        require(uri.scheme?.lowercase() == "https" || local) { "يجب استخدام HTTPS خارج الشبكة المحلية" }
        return normalized
    }

    private fun validateMediaUrl(config: GatewayConfig, mediaUrl: String) {
        val media = URI(mediaUrl)
        val base = URI(validateBaseUrl(config.baseUrl))
        require(media.scheme == base.scheme && media.host == base.host) { "رابط النتيجة لا ينتمي إلى Gateway المضبوط" }
    }

    private fun String.requireNonBlank(message: String): String = takeIf { it.isNotBlank() } ?: error(message)
}
