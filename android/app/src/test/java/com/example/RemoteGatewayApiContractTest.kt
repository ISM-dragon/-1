package com.example

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.remote.data.GatewayApiClient
import com.example.remote.model.GatewayConfig
import com.example.remote.model.GatewayJobState
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteGatewayApiContractTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GatewayApiClient
    private lateinit var config: GatewayConfig

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        client = GatewayApiClient(context.contentResolver)
        config = GatewayConfig(server.url("/").toString().removeSuffix("/"), "test-session-token")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun connectionChecksHealthCapabilitiesAndSession() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"status\":\"ok\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"gateway\":true,\"pipeline\":true,\"ffmpeg\":true,\"runtime_ready\":false}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"authenticated\":true,\"product\":\"ISM\",\"api_version\":\"v1\"}"))

        val result = kotlinx.coroutines.runBlocking { client.checkConnection(config) }.getOrThrow()

        assertTrue(result.ok)
        assertTrue(result.authenticated)
        assertEquals(false, result.runtimeReady)
        assertEquals(3, server.requestCount)
        assertEquals("Bearer test-session-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun uploadCreatePollAndDownloadUsePublicContractFields() {
        val source = File.createTempFile("ism-upload", ".mp4").apply { writeBytes(ByteArray(4096) { 7 }) }
        val downloaded = File.createTempFile("ism-result", ".mp4")
        val progress = mutableListOf<Int>()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"upl_test\",\"status\":\"done\",\"source\":\"${server.url("/v1/sources/jobs/upl_test/media/source.mp4")}\",\"filename\":\"source.mp4\",\"bytes\":4096}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"proc_test\",\"job_id\":\"proc_test\",\"status\":\"queued\",\"state\":\"QUEUED\"}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"proc_test\",\"job_id\":\"proc_test\",\"state\":\"RENDERING\",\"progress\":0.72,\"stage\":\"render\",\"message\":\"Rendering\"}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"proc_test\",\"job_id\":\"proc_test\",\"state\":\"COMPLETED\",\"progress\":1.0,\"results\":{\"render\":{\"outputs\":[{\"id\":\"clip_1\",\"title\":\"First clip\",\"start\":2,\"end\":8,\"duration\":6,\"score\":91,\"path\":\"${server.url("/v1/processing/jobs/proc_test/media/clip_1.mp4") }\"}]}}}"))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setBody("real-mp4-result"))

            val uploaded = kotlinx.coroutines.runBlocking {
                client.uploadVideo(config, Uri.fromFile(source)) { progress += it }
            }.getOrThrow()
            val created = kotlinx.coroutines.runBlocking { client.createJob(config, uploaded.sourceUrl, "stable-key") }.getOrThrow()
            val running = kotlinx.coroutines.runBlocking { client.getJob(config, created.id) }.getOrThrow()
            val completed = kotlinx.coroutines.runBlocking { client.getJob(config, created.id) }.getOrThrow()
            kotlinx.coroutines.runBlocking { client.downloadMedia(config, completed.clips.single().mediaUrl, downloaded) }.getOrThrow()

            assertEquals("upl_test", uploaded.id)
            assertTrue(progress.contains(100))
            assertEquals("proc_test", created.id)
            assertEquals(GatewayJobState.RENDERING, running.state)
            assertEquals(72, running.progress)
            assertEquals(GatewayJobState.COMPLETED, completed.state)
            assertEquals("First clip", completed.clips.single().title)
            assertEquals(91, completed.clips.single().score)
            assertEquals("real-mp4-result", downloaded.readText())
            assertEquals("POST", server.takeRequest().method)
            assertEquals("POST", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
        } finally {
            source.delete()
            downloaded.delete()
        }
    }
}
