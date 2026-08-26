package com.example

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.contract.ApiContractClient
import com.example.data.contract.ClipArtifact
import com.example.data.model.GatewayConfig
import com.example.data.contract.ProcessingRequest
import com.example.data.contract.toJobResource
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.file.Files
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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
        val artifact = ClipArtifact("clip_1", "Demo", "http://127.0.0.1:${server.address.port}/clip.mp4", 0, 5, 5, 80, "", "clip.mp4")
        assertTrue(client.download(config, artifact, target).isSuccess)
        assertTrue(target.length() > 0)
        target.delete()
    }

    @Test
    fun `json mapper keeps fractional progress and artifact manifest`() {
        val payload = JSONObject("""
            {"id":"p1","state":"COMPLETED","fraction":1,"artifacts":[{"id":"c1","title":"Hook","url":"https://cdn/clip.mp4","start":2,"end":12,"duration":10,"score":91}]}
        """.trimIndent()).toJobResource()
        assertEquals(100, payload.progress)
        assertEquals(1, payload.artifacts.size)
        assertEquals("Hook", payload.artifacts.first().title)
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
