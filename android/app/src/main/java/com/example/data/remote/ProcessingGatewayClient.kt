package com.example.data.remote

import android.content.ContentResolver
import android.net.Uri
import com.example.data.model.GatewayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.min

/**
 * Remote processing bridge. Gemini credentials never cross this boundary;
 * the Gateway owns the Python pipeline and its server-side provider secrets.
 */
class ProcessingGatewayClient(
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
        val gatewayJobId: String,
        val clips: List<RemoteClip>
    )

    suspend fun process(
        config: GatewayConfig,
        sourceUri: String,
        captionTheme: String,
        mode: String,
        onProgress: suspend (Progress) -> Unit,
        onJobCreated: suspend (String) -> Unit = {},
        existingGatewayJobId: String? = null
    ): Result<RemoteResult> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val gatewayJobId = existingGatewayJobId?.trim().orEmpty().ifBlank {
                val localUri = Uri.parse(sourceUri)
                val upload = uploadResumable(baseUrl, config.token, localUri) { percent ->
                    onProgress(Progress(percent, "UPLOADING", "رفع الفيديو إلى Gateway: $percent%"))
                }
                onProgress(Progress(12, "UPLOADED", "تم رفع الفيديو إلى Gateway بشكل خاص"))
                start(baseUrl, config.token, upload, captionTheme, mode, UUID.randomUUID().toString())
            }
            onJobCreated(gatewayJobId)
            if (existingGatewayJobId?.isNotBlank() == true) {
                val initial = status(baseUrl, config.token, gatewayJobId)
                val initialState = initial.optString("state").uppercase()
                val initialStatus = initial.optString("status").lowercase()
                if (initialState in setOf("INTERRUPTED", "FAILED") || initialStatus in setOf("failed", "error")) {
                    resume(config, gatewayJobId).getOrThrow()
                }
            }
            var lastStatus = "queued"
            var completedResult: RemoteResult? = null
            while (completedResult == null) {
                val statusPayload = status(baseUrl, config.token, gatewayJobId)
                val fraction = statusPayload.optDouble("fraction", 0.0).toFloat().coerceIn(0f, 1f)
                val percent = (15 + fraction * 80f).toInt().coerceIn(15, 95)
                val stage = statusPayload.optString("stage", statusPayload.optString("status", "processing"))
                val message = statusPayload.optString("message", "جاري تنفيذ المعالجة على Gateway")
                if (stage != lastStatus || percent >= 95) {
                    onProgress(Progress(percent, stage, message))
                    lastStatus = stage
                }
                val state = statusPayload.optString("state").uppercase()
                when {
                    state == "COMPLETED" || statusPayload.optString("status") in setOf("done", "completed", "succeeded") -> {
                        onProgress(Progress(100, "COMPLETED", "اكتملت المعالجة البعيدة"))
                        completedResult = RemoteResult(gatewayJobId, parseClips(statusPayload))
                    }
                    state == "CANCELLED" || statusPayload.optString("status") == "cancelled" -> error("ألغى Gateway مهمة المعالجة")
                    state == "FAILED" || statusPayload.optString("status") in setOf("failed", "error") -> error(statusPayload.optString("error", "فشلت معالجة Gateway"))
                    state == "INTERRUPTED" -> onProgress(Progress(percent, "INTERRUPTED", "توقفت المهمة مؤقتاً؛ يمكن استئنافها من Gateway"))
                }
                if (completedResult == null) delay(POLL_INTERVAL_MS)
            }
            Result.success(requireNotNull(completedResult))
        } catch (error: Exception) {
            Result.failure<RemoteResult>(error)
        }
    }

    private suspend fun uploadResumable(
        baseUrl: String,
        token: String,
        sourceUri: Uri,
        onProgress: suspend (Int) -> Unit
    ): String {
        val totalBytes = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
        require(totalBytes > 0L) { "تعذر معرفة حجم ملف الفيديو للرفع المتقطع" }
        val checksum = sha256(sourceUri)
        val filename = sourceUri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "source.mp4" }
        val initialized = requestJson(
            "$baseUrl/v1/sources/uploads",
            token,
            "POST",
            JSONObject()
                .put("filename", filename)
                .put("bytes", totalBytes)
                .put("sha256", checksum)
        )
        val uploadId = initialized.optString("id").takeIf { it.isNotBlank() }
            ?: error("Gateway لم يُرجع معرّف جلسة الرفع")
        var offset = initialized.optLong("offset", 0L).coerceIn(0L, totalBytes)
        if (initialized.optString("status").equals("completed", ignoreCase = true) || offset == totalBytes) {
            return initialized.optString("source").takeIf { it.isNotBlank() }
                ?: completeUpload(baseUrl, token, uploadId)
        }

        while (offset < totalBytes) {
            val chunkLength = min(UPLOAD_CHUNK_BYTES.toLong(), totalBytes - offset).toInt()
            val chunk = readChunk(sourceUri, offset, chunkLength)
            val connection = openConnection("$baseUrl/v1/sources/uploads/${URI.create(uploadId).toASCIIString()}", token, "PUT").apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-Upload-Offset", offset.toString())
                setRequestProperty("Content-Range", "bytes $offset-${offset + chunk.size - 1}/$totalBytes")
                setFixedLengthStreamingMode(chunk.size)
            }
            try {
                connection.outputStream.use { it.write(chunk) }
                val response = readJson(connection)
                val nextOffset = response.optLong("offset", offset + chunk.size)
                require(nextOffset == offset + chunk.size && nextOffset <= totalBytes) {
                    "Gateway أعاد offset غير متوقع لجلسة الرفع"
                }
                offset = nextOffset
                onProgress((offset.toDouble() / totalBytes.toDouble() * 10.0).toInt().coerceIn(0, 10))
            } finally {
                connection.disconnect()
            }
        }
        onProgress(10)
        return completeUpload(baseUrl, token, uploadId)
    }

    private fun completeUpload(baseUrl: String, token: String, uploadId: String): String {
        val json = requestJson(
            "$baseUrl/v1/sources/uploads/${URI.create(uploadId).toASCIIString()}/complete",
            token,
            "POST",
            JSONObject()
        )
        return json.optString("source").takeIf { it.isNotBlank() }
            ?: error("Gateway لم يُرجع رابط المصدر بعد إكمال الرفع")
    }

    private fun sha256(sourceUri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = contentResolver.openInputStream(sourceUri) ?: error("تعذر فتح ملف الفيديو للتحقق")
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun readChunk(sourceUri: Uri, offset: Long, length: Int): ByteArray {
        val input = contentResolver.openInputStream(sourceUri) ?: error("تعذر فتح ملف الفيديو لاستئناف الرفع")
        input.use { source ->
            var remaining = offset
            while (remaining > 0L) {
                val skipped = source.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                } else if (source.read() >= 0) {
                    remaining -= 1L
                } else {
                    error("تعذر الوصول إلى موضع الاستئناف في الفيديو")
                }
            }
            val chunk = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = source.read(chunk, read, length - read)
                if (count < 0) break
                if (count == 0) continue
                read += count
            }
            require(read > 0) { "تعذر قراءة جزء من الفيديو" }
            return if (read == chunk.size) chunk else chunk.copyOf(read)
        }
    }

    private fun requestJson(url: String, token: String, method: String, body: JSONObject): JSONObject {
        val connection = openConnection(url, token, method).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun start(baseUrl: String, token: String, sourceUrl: String, captionTheme: String, mode: String, idempotencyKey: String): String {
        val connection = openConnection("$baseUrl/v1/processing/jobs", token, "POST").apply {
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            doOutput = true
        }
        return try {
            val body = JSONObject()
                .put("source", sourceUrl)
                .put("llm", "gemini")
                .put("captions", captionTheme.ifBlank { "classic" })
                .put("mode", mode.ifBlank { "balanced" })
                .put("idempotency_key", idempotencyKey)
                .toString()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val json = readJson(connection)
            json.optString("id").takeIf { it.isNotBlank() }
                ?: error("Gateway لم يُرجع معرف المهمة")
        } finally {
            connection.disconnect()
        }
    }

    private fun status(baseUrl: String, token: String, jobId: String): JSONObject {
        val connection = openConnection("$baseUrl/v1/processing/jobs/${URI.create(jobId).toASCIIString()}", token, "GET")
        return try { readJson(connection) } finally { connection.disconnect() }
    }

    suspend fun cancel(config: GatewayConfig, jobId: String): Result<JSONObject> = control(config, jobId, "cancel")

    suspend fun retry(config: GatewayConfig, jobId: String): Result<JSONObject> = control(config, jobId, "retry")

    suspend fun resume(config: GatewayConfig, jobId: String): Result<JSONObject> = control(config, jobId, "resume")

    private suspend fun control(config: GatewayConfig, jobId: String, action: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val connection = openConnection("$baseUrl/v1/processing/jobs/${URI.create(jobId).toASCIIString()}/$action", config.token, "POST").apply {
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                doOutput = true
            }
            try {
                connection.outputStream.use { it.write("{}".toByteArray(Charsets.UTF_8)) }
                readJson(connection)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parseClips(status: JSONObject): List<RemoteClip> {
        val results = status.optJSONObject("results") ?: return emptyList()
        val render = results.optJSONObject("render") ?: return emptyList()
        val outputs = render.optJSONArray("outputs") ?: JSONArray()
        return buildList {
            for (index in 0 until outputs.length()) {
                val item = outputs.optJSONObject(index) ?: continue
                val url = item.optString("path").takeIf { it.startsWith("http") } ?: continue
                val start = item.optInt("start", item.optInt("start_time", 0))
                val end = item.optInt("end", item.optInt("end_time", start + item.optInt("duration", 0)))
                add(
                    RemoteClip(
                        title = item.optString("title", "Clip ${index + 1}"),
                        startTimeSec = start,
                        endTimeSec = end.coerceAtLeast(start),
                        durationSec = item.optInt("duration", (end - start).coerceAtLeast(0)),
                        score = item.optInt("score", item.optInt("final_score", 0)),
                        transcript = item.optString("transcript"),
                        mediaUrl = url
                    )
                )
            }
        }
    }

    suspend fun download(config: GatewayConfig, mediaUrl: String, destination: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = openConnection(mediaUrl, config.token, "GET")
            try {
                connection.inputStream.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
                require(destination.isFile && destination.length() > 0) { "Gateway أعاد ملفاً فارغاً" }
                destination
            } finally {
                connection.disconnect()
            }
        }
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
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IOException("Gateway HTTP $code: ${body.take(300)}")
        return JSONObject(body)
    }

    private fun validateBaseUrl(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        require(normalized.isNotBlank()) { "Gateway URL غير مضبوط" }
        val uri = URI(normalized)
        require(uri.scheme?.lowercase() == "https") { "يجب استخدام HTTPS مع private Processing Gateway" }
        return normalized
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
        private const val UPLOAD_CHUNK_BYTES = 4 * 1024 * 1024
    }
}
