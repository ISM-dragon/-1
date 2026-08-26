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
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

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
        onJobCreated: suspend (String) -> Unit = {}
    ): Result<RemoteResult> = withContext(Dispatchers.IO) {
        try {
            val baseUrl = validateBaseUrl(config.baseUrl)
            val localUri = Uri.parse(sourceUri)
            val upload = upload(baseUrl, config.token, localUri) { percent ->
                onProgress(Progress(percent, "UPLOADING", "رفع الفيديو إلى Gateway: $percent%"))
            }
            onProgress(Progress(12, "UPLOADED", "تم رفع الفيديو إلى Gateway بشكل خاص"))
            val gatewayJobId = start(baseUrl, config.token, upload, captionTheme, mode, UUID.randomUUID().toString())
            onJobCreated(gatewayJobId)
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

    private suspend fun upload(
        baseUrl: String,
        token: String,
        sourceUri: Uri,
        onProgress: suspend (Int) -> Unit
    ): String {
        val total = contentResolver.openAssetFileDescriptor(sourceUri, "r")?.use { it.length } ?: -1L
        require(total > 0L) { "تعذر معرفة حجم الفيديو للرفع" }
        val digest = sha256(sourceUri, total)
        val filename = sourceUri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "source.mp4"
        val session = requestJson(
            "$baseUrl/v1/sources/uploads",
            token,
            "POST",
            JSONObject().put("filename", filename).put("bytes", total).put("sha256", digest)
        )
        val uploadId = session.optString("id").takeIf { it.isNotBlank() } ?: error("Gateway لم يُرجع معرّف جلسة الرفع")
        var offset = session.optLong("offset", 0L).coerceIn(0L, total)
        val chunkBytes = session.optLong("chunk_bytes", DEFAULT_UPLOAD_CHUNK_BYTES.toLong()).coerceIn(1L, MAX_UPLOAD_CHUNK_BYTES.toLong())
        if (session.optString("status").equals("done", true) || session.optString("status").equals("completed", true)) {
            onProgress(10)
            return session.optString("source").takeIf { it.isNotBlank() } ?: error("جلسة الرفع المكتملة بلا مصدر")
        }
        while (offset < total) {
            val length = minOf(chunkBytes, total - offset)
            val response = uploadChunk(baseUrl, token, uploadId, sourceUri, total, offset, length)
            val nextOffset = response.optLong("offset", offset + length)
            require(nextOffset == offset + length) { "Gateway أعاد offset غير متوقع لجلسة الرفع" }
            offset = nextOffset
            onProgress((offset * 10L / total).toInt().coerceIn(0, 10))
        }
        val completed = requestJson(
            "$baseUrl/v1/sources/uploads/${URI.create(uploadId).toASCIIString()}/complete",
            token,
            "POST",
            JSONObject()
        )
        onProgress(10)
        return completed.optString("source").takeIf { it.isNotBlank() } ?: error("Gateway لم يُرجع رابط المصدر المرفوع")
    }

    private fun sha256(sourceUri: Uri, total: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = contentResolver.openInputStream(sourceUri) ?: throw IOException("تعذر فتح ملف الفيديو للرفع")
        var read = 0L
        input.use { source ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                read += count
            }
        }
        require(read == total) { "تغير حجم الفيديو أثناء الرفع" }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uploadChunk(baseUrl: String, token: String, uploadId: String, sourceUri: Uri, total: Long, offset: Long, length: Long): JSONObject {
        val connection = openConnection("$baseUrl/v1/sources/uploads/${URI.create(uploadId).toASCIIString()}", token, "PUT").apply {
            doOutput = true
            setRequestProperty("Content-Type", contentResolver.getType(sourceUri) ?: "video/mp4")
            setRequestProperty("X-Upload-Offset", offset.toString())
            setRequestProperty("Content-Range", "bytes $offset-${offset + length - 1}/$total")
            setFixedLengthStreamingMode(length)
        }
        return try {
            val input = contentResolver.openInputStream(sourceUri) ?: throw IOException("تعذر فتح ملف الفيديو للرفع")
            input.use { source ->
                skipFully(source, offset)
                connection.outputStream.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = length
                    while (remaining > 0L) {
                        val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("انتهى مصدر الفيديو قبل اكتمال الجزء")
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

    private fun requestJson(url: String, token: String, method: String, body: JSONObject? = null): JSONObject {
        val connection = openConnection(url, token, method).apply {
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            body?.let { connection.outputStream.use { output -> output.write(it.toString().toByteArray(Charsets.UTF_8)) } }
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
        private const val DEFAULT_UPLOAD_CHUNK_BYTES = 16 * 1024 * 1024
        private const val MAX_UPLOAD_CHUNK_BYTES = 64 * 1024 * 1024
    }
}
