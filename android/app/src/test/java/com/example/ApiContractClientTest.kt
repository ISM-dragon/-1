package com.example

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.contract.ApiContractClient
import com.example.data.contract.ApiContractException
import com.example.data.contract.ClipArtifact
import com.example.data.model.GatewayConfig
import com.example.data.contract.ProcessingRequest
import com.example.data.contract.toJobResource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApiContractClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: ApiContractClient
    private val jobsJson = """
        {"id":"proc_test","job_id":"proc_test","status":"running","state":"RENDERING","stage":"RENDERING","fraction":0.42,"message":"Rendering","retry_count":1,"recoverable":true,"artifacts":[]}
    """.trimIndent()

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/health") { exchange -> respond(exchange, "{\"ok\":true,\"status\":\"ready\"}") }
        server.createContext("/v1/processing/jobs") { exchange ->
            when (exchange.requestMethod) {
                "POST" -> respond(exchange, "{\"id\":\"proc_test\",\"job_id\":\"proc_test\",\"status\":\"queued\",\"state\":\"QUEUED\"}")
                "GET" -> respond(exchange, jobsJson)
                else -> respond(exchange, "{}")
            }
        }
        server.createContext("/v1/processing/jobs/proc_test/cancel") { exchange -> respond(exchange, "{\"id\":\"proc_test\",\"status\":\"cancelled\",\"state\":\"CANCELLED\"}") }
        server.createContext("/v1/processing/jobs/proc_error") { exchange ->
            respondStatus(exchange, 422, "{\"detail\":{\"code\":\"MEDIA_INVALID\",\"message\":\"Uploaded file is not a readable video.\"},\"request_id\":\"req_media_1\"}")
        }
        server.createContext("/clip.mp4") { exchange -> respond(exchange, "real-mp4-bytes", "video/mp4") }
        server.start()
        client = ApiContractClient(ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver)
    }

    @After
    fun tearDown() = server.stop(0)

    @Test
    fun `health create poll and cancel follow contract`() = runBlocking<Unit> {
        val config = GatewayConfig("http://127.0.0.1:${server.address.port}", "token")
        assertEquals("ready", client.health(config).getOrThrow())
        val created = client.createJob(config, ProcessingRequest("https://gateway/source.mp4", idempotencyKey = "android-test-1")).getOrThrow()
        assertEquals("proc_test", created.id)
        assertEquals(42, client.getJob(config, "proc_test").getOrThrow().progress)
        assertEquals("CANCELLED", client.cancel(config, "proc_test").getOrThrow().state.name)
    }

    @Test
    fun `artifact download is persisted as a non-empty file`() = runBlocking<Unit> {
        val config = GatewayConfig("http://127.0.0.1:${server.address.port}", "token")
        val target = Files.createTempFile("ism-clip", ".mp4").toFile()
        val artifact = ClipArtifact("clip_1", "Demo", "http://127.0.0.1:${server.address.port}/clip.mp4", 0, 5, 5, 80, "", "clip.mp4", "04b8ccc5f19b8f3bed371fec98c184d98aaa78c33fa08b2015cfe3c453bd706f")
        assertTrue(client.download(config, artifact, target).isSuccess)
        assertTrue(target.length() > 0)
        target.delete()
    }

    @Test
    fun `object error detail is parsed into a stable api error`() = runBlocking<Unit> {
        val config = GatewayConfig("http://127.0.0.1:${server.address.port}", "token")
        val failure = client.getJob(config, "proc_error").exceptionOrNull()
        require(failure is ApiContractException)
        assertEquals("MEDIA_INVALID", failure.apiError.code)
        assertEquals("Uploaded file is not a readable video.", failure.apiError.message)
        assertEquals("req_media_1", failure.apiError.requestId)
        assertTrue(!failure.apiError.retryable)
    }

    @Test
    fun `artifact download rejects a checksum mismatch`() = runBlocking<Unit> {
        val config = GatewayConfig("http://127.0.0.1:${server.address.port}", "token")
        val target = Files.createTempFile("ism-clip-bad", ".mp4").toFile()
        val artifact = ClipArtifact("clip_bad", "Bad", "http://127.0.0.1:${server.address.port}/clip.mp4", 0, 5, 5, 80, "", "clip.mp4", "0".repeat(64))
        assertTrue(client.download(config, artifact, target).isFailure)
        assertTrue(!target.exists() || target.length() == 0L)
        target.delete()
    }

    @Test
    fun `resumable upload sends ranges and completes the source`() = runBlocking<Unit> {
        val source = ByteArray(190_000) { (it % 251).toByte() }
        val sourceFile = Files.createTempFile("ism-source", ".mp4").toFile().apply { writeBytes(source) }
        val received = ByteArrayOutputStream()
        val ranges = mutableListOf<String?>()
        val offsets = mutableListOf<String?>()
        var expectedOffset = 0L
        var uploadInitBody = ""
        server.createContext("/v1/sources/uploads") { exchange ->
            if (exchange.requestMethod == "POST") {
                uploadInitBody = exchange.requestBody.bufferedReader().use { it.readText() }
                respond(exchange, "{\"id\":\"upl_test\",\"status\":\"uploading\",\"offset\":0,\"chunk_bytes\":65536}")
            } else {
                respond(exchange, "{}")
            }
        }
        server.createContext("/v1/sources/uploads/upl_test") { exchange ->
            ranges += exchange.requestHeaders.getFirst("Content-Range")
            offsets += exchange.requestHeaders.getFirst("X-Upload-Offset")
            exchange.requestBody.use { it.copyTo(received) }
            expectedOffset = received.size().toLong()
            respond(exchange, "{\"id\":\"upl_test\",\"status\":\"uploading\",\"offset\":$expectedOffset,\"chunk_bytes\":65536}")
        }
        server.createContext("/v1/sources/uploads/upl_test/complete") { exchange ->
            respond(exchange, "{\"id\":\"upl_test\",\"status\":\"done\",\"source\":\"http://127.0.0.1:${server.address.port}/source.mp4\",\"filename\":\"source.mp4\"}")
        }

        val uploaded = client.upload(
            GatewayConfig("http://127.0.0.1:${server.address.port}", "token"),
            Uri.fromFile(sourceFile)
        ) { }
            .getOrThrow()
        assertEquals("upl_test", uploaded.id)
        assertEquals(source.size.toLong(), uploaded.bytes)
        assertEquals(source.size, received.size())
        assertArrayEquals(source, received.toByteArray())
        assertEquals(listOf("0", "65536", "131072"), offsets)
        assertEquals(
            listOf("bytes 0-65535/${source.size}", "bytes 65536-131071/${source.size}", "bytes 131072-189999/${source.size}"),
            ranges
        )
        assertTrue(uploadInitBody.contains("sha256"))
        sourceFile.delete()
    }

    @Test
    fun `artifact download rejects a different origin`() = runBlocking<Unit> {
        val config = GatewayConfig("http://127.0.0.1:${server.address.port}", "token")
        val target = Files.createTempFile("ism-external", ".mp4").toFile()
        val artifact = ClipArtifact("clip_external", "External", "https://evil.example/clip.mp4", 0, 5, 5, 80, "", "clip.mp4")
        assertTrue(client.download(config, artifact, target).isFailure)
        target.delete()
    }

    @Test
    fun `json mapper keeps fractional progress and artifact manifest`() {
        val payload = JSONObject("""
            {"id":"p1","state":"COMPLETED","fraction":1,"artifacts":[{"id":"c1","title":"Hook","url":"https://cdn/clip.mp4","start":2,"end":12,"duration":10,"score":91,"sha256":"04b8ccc5f19b8f3bed371fec98c184d98aaa78c33fa08b2015cfe3c453bd706f"}]}
        """.trimIndent()).toJobResource()
        assertEquals(100, payload.progress)
        assertEquals(1, payload.artifacts.size)
        assertEquals("Hook", payload.artifacts.first().title)
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String, contentType: String = "application/json") {
        respondStatus(exchange, 200, body, contentType)
    }

    private fun respondStatus(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
