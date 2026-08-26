package com.example.core.network

import android.content.ContentResolver
import android.net.Uri
import com.example.core.model.ApiException
import com.example.core.model.ErrorState
import com.example.core.model.JobState
import com.example.data.model.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Thin, stateless HTTP boundary for the authenticated private Gateway.
 * It never starts Python-side tooling and never treats local state as authoritative.
 */
class ApiClient(
    private val contentResolver: ContentResolver,
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    data class UploadResource(
        val id: String,
        val source: String,
        val filename: String,
        val bytes: Long,
        val sha256: String?
    )

    data class RenderRequest(
        val source: String,
        val llm: String = "gemini",
        val captions: String = "classic",
        val mode: String = "balanced",
        val idempotencyKey: String = UUID.randomUUID().toString()
    )

    data class RemoteOutput(
        val title: String,
        val startTimeSec: Int,
        val endTimeSec: Int,
        val durationSec: Int,
        val score: Int,
        val transcript: String,
        val mediaUrl: String
    )

    data class RemoteJob(
        val id: String,
        val state: JobState,
        val compatibilityStatus: String,
        val progress: Int,
        val stage: String,
        val message: String,
        val retryCount: Int,
        val recoverable: Boolean,
        val error: ErrorState?,
        val correlationId: String?,
        val requestId: String?,
        val outputs: List<RemoteOutput>,
        val raw: JSONObject
    )

    suspend fun upload(
        config: GatewayConfig,
        sourceUri: Uri,
        onProgress: (suspend (Int) -> Unit)? = null
    ): Result<UploadResource> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val descriptor = contentResolver.openAssetFileDescriptor(sourceUri, "r")
            val length = descriptor?.use { it.length } ?: -1L
            val requestBody = object : RequestBody() {
                override fun contentType() = "video/mp4".toMediaType()
                override fun contentLength() = length
                override fun writeTo(sink: BufferedSink) {
                    val input = contentResolver.openInputStream(sourceUri)
                        ?: throw IOException("تعذر فتح ملف الفيديو للرفع")
                    var written = 0L
                    var lastReported = 0L
                    input.use { stream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = stream.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            written += read
                            val now = System.currentTimeMillis()
                            if (onProgress != null && (now - lastReported >= 250L || (length > 0 && written >= length))) {
                                val percent = if (length > 0) (written * 100L / length).toInt().coerceIn(0, 100) else 0
                                kotlinx.coroutines.runBlocking { onProgress(percent) }
                                lastReported = now
                            }
                        }
                    }
                }
            }
            val request = requestBuilder("$baseUrl/v1/sources/upload", config.token)
                .post(requestBody)
                .header("Content-Type", "video/mp4")
                .build()
            val json = executeJson(request)
            UploadResource(
                id = json.optString("id").ifBlank { json.optString("upload_id") },
                source = json.optString("source").ifBlank { throw IOException("Gateway لم يُرجع رابط المصدر") },
                filename = json.optString("filename", "source.mp4"),
                bytes = json.optLong("bytes", length),
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() }
            )
        }
    }

    suspend fun createJob(config: GatewayConfig, request: RenderRequest): Result<RemoteJob> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val body = JSONObject()
                .put("source", request.source)
                .put("llm", request.llm)
                .put("captions", request.captions)
                .put("mode", request.mode)
                .put("idempotency_key", request.idempotencyKey)
            val httpRequest = requestBuilder("$baseUrl/v1/processing/jobs", config.token)
                .post(jsonBody(body))
                .build()
            parseJob(executeJson(httpRequest), baseUrl)
        }
    }

    /** Rendering is requested by the processing-job contract; this alias keeps that intent explicit. */
    suspend fun render(config: GatewayConfig, request: RenderRequest): Result<RemoteJob> = createJob(config, request)

    suspend fun getJob(config: GatewayConfig, jobId: String): Result<RemoteJob> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val request = requestBuilder(jobUrl(baseUrl, jobId), config.token).get().build()
            parseJob(executeJson(request), baseUrl)
        }
    }

    /** The current contract has an authoritative per-job GET, not a list route. */
    suspend fun getJobs(config: GatewayConfig, jobIds: Collection<String>): Result<List<RemoteJob>> = withContext(Dispatchers.IO) {
        runCatching {
            jobIds.map { id -> getJob(config, id).getOrThrow() }
        }
    }

    suspend fun poll(
        config: GatewayConfig,
        jobId: String,
        onUpdate: suspend (RemoteJob) -> Unit = {}
    ): Result<RemoteJob> = withContext(Dispatchers.IO) {
        runCatching {
            var terminalJob: RemoteJob? = null
            while (terminalJob == null) {
                val job = getJob(config, jobId).getOrThrow()
                onUpdate(job)
                if (job.state.isTerminal || job.state == JobState.INTERRUPTED) {
                    terminalJob = job
                } else {
                    kotlinx.coroutines.delay(POLL_INTERVAL_MS)
                }
            }
            requireNotNull(terminalJob)
        }
    }

    suspend fun cancel(config: GatewayConfig, jobId: String): Result<RemoteJob> = control(config, jobId, "cancel")
    suspend fun retry(config: GatewayConfig, jobId: String): Result<RemoteJob> = control(config, jobId, "retry")
    suspend fun resume(config: GatewayConfig, jobId: String): Result<RemoteJob> = control(config, jobId, "resume")

    suspend fun results(config: GatewayConfig, jobId: String): Result<List<RemoteOutput>> =
        getJob(config, jobId).map { it.outputs }

    suspend fun download(config: GatewayConfig, mediaUrl: String, destination: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val resolvedUrl = resolveMediaUrl(baseUrl, mediaUrl)
            destination.parentFile?.mkdirs()
            val partial = File(destination.parentFile ?: destination.absoluteFile.parentFile!!, "${destination.name}.part")
            partial.delete()
            val request = requestBuilder(resolvedUrl, config.token).get().build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw apiException(response.code, response.body?.string(), response.header("X-Request-ID"))
                    val body = response.body ?: throw IOException("استجابة الوسائط فارغة")
                    body.byteStream().use { input -> partial.outputStream().use { output -> input.copyTo(output) } }
                }
                require(partial.isFile && partial.length() > 0) { "Gateway أعاد ملفًا فارغًا" }
                if (destination.exists() && !destination.delete()) throw IOException("تعذر استبدال الملف المحلي")
                require(partial.renameTo(destination)) { "تعذر تثبيت الملف المنزّل" }
                destination
            } catch (error: Exception) {
                partial.delete()
                throw error
            }
        }
    }

    private suspend fun control(config: GatewayConfig, jobId: String, action: String): Result<RemoteJob> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val request = requestBuilder("${jobUrl(baseUrl, jobId)}/$action", config.token)
                .post(jsonBody(JSONObject()))
                .build()
            parseJob(executeJson(request), baseUrl)
        }
    }

    private fun parseJob(json: JSONObject, baseUrl: String): RemoteJob {
        val status = json.optString("status")
        val state = JobState.fromWire(json.optString("state"), status)
        val errorObject = json.optJSONObject("error")
        val errorCode = errorObject?.optString("code").takeUnless { it.isNullOrBlank() }
            ?: json.optString("error_code").takeUnless { it.isNullOrBlank() }
        val errorMessage = errorObject?.optString("message").takeUnless { it.isNullOrBlank() }
            ?: json.optString("error").takeUnless { it.isNullOrBlank() }
        val error = if (state == JobState.FAILED || !errorCode.isNullOrBlank()) {
            ErrorState.terminalJob(
                code = errorCode ?: "PROCESSING_FAILED",
                message = errorMessage ?: "فشلت معالجة المهمة على الـGateway.",
                recoverable = json.optBoolean("recoverable", false),
                requestId = json.optString("request_id").takeIf { it.isNotBlank() }
            )
        } else null
        return RemoteJob(
            id = json.optString("id").ifBlank { json.optString("job_id") },
            state = state,
            compatibilityStatus = status,
            progress = progressOf(json),
            stage = json.optString("stage", state.name),
            message = json.optString("message", ""),
            retryCount = json.optInt("retry_count", 0),
            recoverable = json.optBoolean("recoverable", state.isRecoverable),
            error = error,
            correlationId = json.optString("correlation_id").takeIf { it.isNotBlank() },
            requestId = json.optString("request_id").takeIf { it.isNotBlank() },
            outputs = parseOutputs(json, baseUrl),
            raw = json
        )
    }

    private fun progressOf(json: JSONObject): Int {
        val percent = when {
            json.has("progress") -> json.optDouble("progress", 0.0).let { if (it <= 1.0) it * 100 else it }
            json.has("fraction") -> json.optDouble("fraction", 0.0) * 100
            else -> 0.0
        }
        return percent.toInt().coerceIn(0, 100)
    }

    private fun parseOutputs(json: JSONObject, baseUrl: String): List<RemoteOutput> {
        val render = json.optJSONObject("results")?.optJSONObject("render") ?: return emptyList()
        val outputs = render.optJSONArray("outputs") ?: JSONArray()
        return buildList {
            for (index in 0 until outputs.length()) {
                val item = outputs.optJSONObject(index) ?: continue
                val path = item.optString("path").takeIf { it.isNotBlank() } ?: continue
                val start = item.optInt("start", item.optInt("start_time", 0))
                val end = item.optInt("end", item.optInt("end_time", start + item.optInt("duration", 0))).coerceAtLeast(start)
                add(RemoteOutput(
                    title = item.optString("title", "Clip ${index + 1}"),
                    startTimeSec = start,
                    endTimeSec = end,
                    durationSec = item.optInt("duration", end - start).coerceAtLeast(0),
                    score = item.optInt("score", item.optInt("final_score", 0)).coerceIn(0, 100),
                    transcript = item.optString("transcript"),
                    mediaUrl = resolveMediaUrl(baseUrl, path)
                ))
            }
        }
    }

    private fun executeJson(request: Request): JSONObject {
        httpClient.newCall(request).execute().use { response ->
            val requestId = response.header("X-Request-ID")
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw apiException(response.code, body, requestId)
            if (body.isBlank()) throw IOException("استجابة الـGateway فارغة")
            return JSONObject(body)
        }
    }

    private fun apiException(status: Int, body: String?, headerRequestId: String?): ApiException {
        val parsed = runCatching { JSONObject(body.orEmpty()) }.getOrNull()
        val nested = parsed?.optJSONObject("error")
        return ApiException(ErrorState.fromHttp(
            statusCode = status,
            code = nested?.optString("code") ?: parsed?.optString("error_code"),
            message = nested?.optString("message") ?: parsed?.optString("detail") ?: parsed?.optString("message"),
            retryable = nested?.optBoolean("retryable")?.takeIf { nested.has("retryable") },
            requestId = nested?.optString("request_id")?.takeIf { it.isNotBlank() } ?: headerRequestId
        ))
    }

    private fun requestBuilder(url: String, token: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", "application/json")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer ${token.trim()}") }

    private fun jsonBody(body: JSONObject): RequestBody = body.toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun jobUrl(baseUrl: String, jobId: String): String = "$baseUrl/v1/processing/jobs/${Uri.encode(jobId)}"

    private fun resolveMediaUrl(baseUrl: String, raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("/")) return "$baseUrl$trimmed"
        val uri = URI(trimmed)
        val baseHost = URI(baseUrl).host?.lowercase().orEmpty()
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) { "رابط الوسائط غير صالح" }
        require(uri.host.lowercase() == baseHost) { "رابط الوسائط يجب أن يعود إلى الـGateway الخاص" }
        return trimmed
    }

    private fun validateBaseUrl(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        require(normalized.isNotBlank()) { "عنوان الـGateway غير مضبوط" }
        val uri = URI(normalized)
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase().orEmpty()
        val local = host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull() in 16..31
        require(scheme == "https" || (scheme == "http" && local)) { "يجب استخدام HTTPS خارج الشبكة المحلية" }
        require(uri.userInfo == null) { "بيانات اعتماد URL غير مسموحة" }
        return normalized
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}
