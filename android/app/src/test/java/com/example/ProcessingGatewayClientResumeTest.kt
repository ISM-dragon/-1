package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.model.GatewayConfig
import com.example.data.remote.ProcessingGatewayClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProcessingGatewayClientResumeTest {
    private lateinit var server: MockWebServer
    private lateinit var client: ProcessingGatewayClient
    private lateinit var config: GatewayConfig

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        client = ProcessingGatewayClient(context.contentResolver)
        config = GatewayConfig(server.url("/").toString().removeSuffix("/"), "test-session-token")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun existingInterruptedJobIsResumedWithoutDuplicateUpload() {
        val mediaUrl = server.url("/v1/processing/jobs/remote-1/media/clip-1.mp4").toString()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"remote-1\",\"state\":\"INTERRUPTED\",\"status\":\"failed\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"remote-1\",\"state\":\"QUEUED\",\"status\":\"queued\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"remote-1\",\"state\":\"COMPLETED\",\"fraction\":1.0,\"stage\":\"COMPLETED\",\"results\":{\"render\":{\"outputs\":[{\"title\":\"Recovered clip\",\"start\":3,\"end\":9,\"duration\":6,\"score\":88,\"path\":\"$mediaUrl\"}]}}}"))

        val result = runBlocking {
            client.process(
                config = config,
                sourceUri = "content://test/source",
                captionTheme = "classic",
                mode = "balanced",
                onProgress = {},
                existingGatewayJobId = "remote-1"
            )
        }.getOrThrow()

        assertEquals("remote-1", result.gatewayJobId)
        assertEquals("Recovered clip", result.clips.single().title)
        assertEquals(88, result.clips.single().score)
        assertEquals(3, server.requestCount)
        val initial = server.takeRequest()
        assertEquals("GET", initial.method)
        assertEquals("/v1/processing/jobs/remote-1", initial.path)
        val resume = server.takeRequest()
        assertEquals("POST", resume.method)
        assertEquals("/v1/processing/jobs/remote-1/resume", resume.path)
        val completed = server.takeRequest()
        assertEquals("GET", completed.method)
        assertEquals("/v1/processing/jobs/remote-1", completed.path)
    }
}
