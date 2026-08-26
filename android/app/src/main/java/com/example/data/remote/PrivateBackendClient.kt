package com.example.data.remote

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.model.GatewayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/**
 * Android-facing private processing contract.
 *
 * This client deliberately talks to /jobs rather than importing any Python
 * module or depending on the internal /v1/processing representation. The
 * server owns upload storage, AI credentials, FFmpeg, checkpoints, and the
 * heavy pipeline runtime.
 */
class PrivateBackendClient(
    private val contentResolver: ContentResolver
) {
    data class Progress(
        val percent: Int,
        val stage: String,
        val message: String
    )

    data class RemoteClip(
        val title: String,
        val startTimeSec: Int,
        val endTimeSec: Int,
        val durationSec: Int,
        val score: Int,
        val transcript: String,
        val mediaUrl: String
    )

    data class RemoteResult(
        val backendJobId: String,
        val clips: List<RemoteClip>
    )

    suspend fun process(
        config: GatewayConfig,
        sourceUri: String,
        captionTheme: String,
        mode: String,
        idempotencyKey: String,
        existingRemoteJobId: String? = null,
        onProgress: suspend (Progress) -> Unit,
        onJobCreated: suspend (String) -> Unit = {}
    ): Result<RemoteResult> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val existing = existingRemoteJobId?.trim().orEmpty()
            val remoteJobId = if (existing.isNotBlank()) {
                onProgress(Progress(15, "RESUMING", "استعادة المهمة المحفوظة من private backend"))
                onJobCreated(existing)
                existing
            } else {
                val created = uploadAndCreateJob(
                    baseUrl = baseUrl,
                    token = config.token,
                    sourceUri = Uri.parse(sourceUri),
                    captionTheme = captionTheme,
                    mode = mode,
                    idempotencyKey = idempotencyKey,
                    onProgress = onProgress
                )
                onJobCreated(created)
                created
            }
            Result.success(pollUntilTerminal(config, remoteJobId, onProgress))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun cancel(config: GatewayConfig, jobId: String): Result<JSONObject> =
        control(config, jobId, "cancel")

    suspend fun resume(config: GatewayConfig, jobId: String): Result<JSONObject> =
        control(config, jobId, "resume")

    private suspend fun uploadAndCreateJob(
        baseUrl: String,
        token: String,
        sourceUri: Uri,
        captionTheme: String,
        mode: String,
        idempotencyKey: String,
        onProgress: suspend (Progress) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val boundary = "----publikclip-${UUID.randomUUID()}"
        val connection = openConnection("$baseUrl/jobs", token, "POST").apply {
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val input = contentResolver.openInputStream(sourceUri)
                ?: throw IOException("تعذر فتح ملف الفيديو للرفع")
            val totalBytes = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
            val filename = displayName(sourceUri)
            val contentType = contentResolver.getType(sourceUri)?.takeIf { it.startsWith("video/") } ?: "video/mp4"
            var writtenBytes = 0L
            DataOutputStream(connection.outputStream).use { output ->
                writeField(output, boundary, "llm", "gemini")
                writeField(output, boundary, "captions", captionTheme.ifBlank { "classic" })
                writeField(output, boundary, "mode", mode.ifBlank { "balanced" })
                writeField(output, boundary, "idempotency_key", idempotencyKey)
                output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Disposition: form-data; name=\"file\"; filename=\"${safeFilename(filename)}\"\r\n".toByteArray(Charsets.UTF_8))
                output.write("Content-Type: $contentType\r\n\r\n".toByteArray(Charsets.UTF_8))
                input.use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastPercent = -1
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        writtenBytes += count
                        val percent = if (totalBytes > 0L) {
                            (writtenBytes.toDouble() / totalBytes * 10.0).toInt().coerceIn(0, 10)
                        } else 0
                        if (percent != lastPercent) {
                            onProgress(Progress(percent, "UPLOADING", "رفع الفيديو إلى private backend: $percent%"))
                            lastPercent = percent
                        }
                    }
                }
                output.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
                output.flush()
            }
            onProgress(Progress(12, "QUEUED", "تم رفع الفيديو وإنشاء مهمة المعالجة"))
            val json = readJson(connection)
            json.optString("job_id", json.optString("id"))
                .takeIf { it.isNotBlank() }
                ?: throw IOException("private backend لم يُرجع معرّف المهمة")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun pollUntilTerminal(
        config: GatewayConfig,
        jobId: String,
        onProgress: suspend (Progress) -> Unit
    ): RemoteResult {
        var resumeAttempted = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val payload = getJob(config, jobId).getOrThrow()
            val state = payload.optString("state").uppercase()
            val percent = normalizeProgress(payload.opt("progress"))
            val stage = payload.optString("current_stage", payload.optString("stage", state.ifBlank { "PROCESSING" }))
            val message = payload.optString("message", "جاري تنفيذ المعالجة على private backend")
            onProgress(Progress(percent, stage, message))
            when (state) {
                "COMPLETED", "DONE", "SUCCEEDED" -> {
                    val result = getResults(config, jobId).getOrThrow()
                    val clips = parseClips(result)
                    if (clips.isEmpty()) throw IOException("اكتملت المهمة دون مقاطع قابلة للتنزيل")
                    onProgress(Progress(100, "COMPLETED", "اكتملت المعالجة البعيدة"))
                    return RemoteResult(jobId, clips)
                }
                "CANCELLED", "CANCELED" -> throw IOException("JOB_CANCELLED: ألغى المستخدم مهمة المعالجة")
                "FAILED", "ERROR" -> {
                    val recoverable = payload.optBoolean("recoverable", false)
                    val error = payload.optJSONArray("errors")?.optJSONObject(0)
                    val code = error?.optString("code")?.takeIf { it.isNotBlank() } ?: "JOB_FAILED"
                    val detail = error?.optString("message")?.takeIf { it.isNotBlank() } ?: "فشلت معالجة الفيديو"
                    throw IOException("$code: $detail${if (recoverable) " (retryable)" else ""}")
                }
                "INTERRUPTED", "RETRY_WAIT" -> if (!resumeAttempted) {
                    resume(config, jobId).getOrThrow()
                    resumeAttempted = true
                }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun getJob(config: GatewayConfig, jobId: String): Result<JSONObject> = runCatching {
        val baseUrl = validateBaseUrl(config.baseUrl)
        val connection = openConnection("$baseUrl/jobs/${Uri.encode(jobId)}", config.token, "GET")
        try { readJson(connection) } finally { connection.disconnect() }
    }

    private fun getResults(config: GatewayConfig, jobId: String): Result<JSONObject> = runCatching {
        val baseUrl = validateBaseUrl(config.baseUrl)
        val connection = openConnection("$baseUrl/jobs/${Uri.encode(jobId)}/results", config.token, "GET")
        try { readJson(connection) } finally { connection.disconnect() }
    }

    private suspend fun control(config: GatewayConfig, jobId: String, action: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val connection = openConnection("$baseUrl/jobs/${Uri.encode(jobId)}/$action", config.token, "POST").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                readJson(connection)
            } finally {
                connection.disconnect()
            }
        }
    }

    suspend fun download(config: GatewayConfig, mediaUrl: String, destination: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = URI(validateBaseUrl(config.baseUrl))
            val media = URI(mediaUrl)
            require(media.scheme.equals(baseUrl.scheme, ignoreCase = true) && media.host.equals(baseUrl.host, ignoreCase = true)) {
                "رابط المقطع لا ينتمي إلى private backend المضبوط"
            }
            val connection = openConnection(mediaUrl, config.token, "GET")
            try {
                destination.parentFile?.mkdirs()
                connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                require(destination.isFile && destination.length() > 0L) { "private backend أعاد ملفاً فارغاً" }
                destination
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseClips(result: JSONObject): List<RemoteClip> {
        val arrays = listOfNotNull(
            result.optJSONArray("clips"),
            result.optJSONArray("artifacts"),
            result.optJSONObject("results")?.optJSONArray("clips"),
            result.optJSONObject("results")?.optJSONArray("artifacts")
        )
        val outputs = arrays.firstOrNull() ?: JSONArray()
        return buildList {
            for (index in 0 until outputs.length()) {
                val item = outputs.optJSONObject(index) ?: continue
                val url = item.optString("download_url", item.optString("url", item.optString("path")))
                    .takeIf { it.startsWith("http", ignoreCase = true) } ?: continue
                val start = item.optInt("start", item.optInt("start_time", 0))
                val end = item.optInt("end", item.optInt("end_time", start + item.optInt("duration", 0))).coerceAtLeast(start)
                add(RemoteClip(
                    title = item.optString("title", "Clip ${index + 1}"),
                    startTimeSec = start,
                    endTimeSec = end,
                    durationSec = item.optInt("duration", end - start).coerceAtLeast(0),
                    score = item.optInt("score", item.optInt("final_score", 0)).coerceIn(0, 100),
                    transcript = item.optString("transcript"),
                    mediaUrl = url
                ))
            }
        }
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return "video.mp4"
    }

    private fun writeField(output: DataOutputStream, boundary: String, name: String, value: String) {
        output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
        output.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray(Charsets.UTF_8))
        output.write(value.toByteArray(Charsets.UTF_8))
        output.write("\r\n".toByteArray(Charsets.UTF_8))
    }

    private fun safeFilename(value: String): String = value
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(120)
        .ifBlank { "video.mp4" }

    private fun normalizeProgress(value: Any?): Int = when (value) {
        is Number -> value.toDouble().let { if (it <= 1.0) (it * 100).toInt() else it.toInt() }.coerceIn(0, 100)
        else -> 0
    }

    private fun openConnection(url: String, token: String, method: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Accept", "application/json")
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
        }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val json = runCatching { JSONObject(body) }.getOrNull()
            val detail = json?.optJSONObject("error")?.optString("message")
                ?: json?.optString("detail")
                ?: body.take(300)
            throw IOException("PRIVATE_BACKEND_HTTP_$code: ${detail.ifBlank { "فشل طلب private backend" }}")
        }
        return JSONObject(body)
    }

    private fun validateBaseUrl(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        require(normalized.isNotBlank()) { "عنوان private backend غير مضبوط" }
        val uri = URI(normalized)
        val host = uri.host.orEmpty().lowercase()
        val local = host == "localhost" || host == "127.0.0.1" || host == "::1"
        require(uri.scheme?.equals("https", ignoreCase = true) == true || (local && uri.scheme?.equals("http", ignoreCase = true) == true)) {
            "يجب استخدام HTTPS مع private backend خارج الاختبار المحلي"
        }
        require(uri.userInfo.isNullOrBlank() && uri.fragment == null) {
            "عنوان private backend غير صالح"
        }
        return normalized
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
