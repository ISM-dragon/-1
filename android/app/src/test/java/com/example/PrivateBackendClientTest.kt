package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.model.GatewayConfig
import com.example.data.remote.PrivateBackendClient
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivateBackendClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PrivateBackendClient
    private lateinit var config: GatewayConfig

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        client = PrivateBackendClient(context.contentResolver)
        config = GatewayConfig(server.url("/").toString().removeSuffix("/"), "private-token")
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun processUsesPrivateJobsContractAndDownloadsResult() {
        val source = File.createTempFile("publikclip-source", ".mp4").apply { writeBytes(ByteArray(256) { 3 }) }
        val destination = File.createTempFile("publikclip-result", ".mp4")
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""
                {"job_id":"job_private","state":"QUEUED","status":"queued","progress":0}
            """.trimIndent()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""
                {"job_id":"job_private","state":"COMPLETED","status":"completed","progress":1.0,"results_available":true}
            """.trimIndent()))
            server.enqueue(MockResponse().setResponseCode(200).setBody("""
                {"job_id":"job_private","status":"completed","clips":[{"clip":0,"title":"Hook","start":2,"end":8,"duration":6,"score":93,"transcript":"hello","download_url":"${server.url("/jobs/job_private/clips/0")}"}]}
            """.trimIndent()))
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "video/mp4").setBody("mp4-bytes"))

            val progress = mutableListOf<Int>()
            val result = kotlinx.coroutines.runBlocking {
                client.process(
                    config = config,
                    sourceUri = android.net.Uri.fromFile(source).toString(),
                    captionTheme = "classic",
                    mode = "balanced",
                    idempotencyKey = "android-test-job-001",
                    onProgress = { progress += it.percent }
                )
            }.getOrThrow()
            kotlinx.coroutines.runBlocking { client.download(config, result.clips.single().mediaUrl, destination) }.getOrThrow()

            assertEquals("job_private", result.backendJobId)
            assertEquals("Hook", result.clips.single().title)
            assertEquals(93, result.clips.single().score)
            assertTrue(progress.contains(100))
            val createRequest = server.takeRequest()
            assertEquals("POST", createRequest.method)
            assertTrue(createRequest.body.readUtf8().contains("name=\"file\""))
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
            assertEquals("mp4-bytes", destination.readText())
        } finally {
            source.delete()
            destination.delete()
        }
    }

    @Test
    fun interruptedExistingJobIsResumedWithoutUploadingAgain() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"job_id":"job_resume","state":"INTERRUPTED","status":"failed","progress":0.42,"recoverable":true}
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"job_id":"job_resume","state":"QUEUED","status":"queued","progress":0.42}
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"job_id":"job_resume","state":"COMPLETED","status":"completed","progress":100}
        """.trimIndent()))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""
            {"job_id":"job_resume","status":"completed","artifacts":[{"clip":0,"title":"Resumed","start":0,"end":5,"download_url":"${server.url("/jobs/job_resume/clips/0")}"}]}
        """.trimIndent()))

        val result = kotlinx.coroutines.runBlocking {
            client.process(
                config = config,
                sourceUri = "content://unused",
                captionTheme = "classic",
                mode = "balanced",
                idempotencyKey = "android-test-resume-001",
                existingRemoteJobId = "job_resume",
                onProgress = {}
            )
        }.getOrThrow()

        assertEquals("job_resume", result.backendJobId)
        assertEquals("Resumed", result.clips.single().title)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }
}
