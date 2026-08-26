package com.example.core

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.example.core.model.ErrorState
import com.example.core.model.JobState
import com.example.core.network.ApiClient
import com.example.data.model.GatewayConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
class AndroidCoreContractTest {
    @Test
    fun jobStateSupportsCanonicalAndCompatibilityValues() {
        assertEquals(JobState.RENDERING, JobState.fromWire("RENDERING", "running"))
        assertEquals(JobState.COMPLETED, JobState.fromWire(null, "done"))
        assertEquals(JobState.CANCELLED, JobState.fromWire("CANCELLED", "cancelled"))
        assertTrue(JobState.INTERRUPTED.isRecoverable)
        assertTrue(JobState.COMPLETED.isTerminal)
    }

    @Test
    fun errorStateMapsStableHttpContract() {
        val error = ErrorState.fromHttp(503, "PIPELINE_UNAVAILABLE", "not ready", requestId = "req_1")
        assertEquals("PIPELINE_UNAVAILABLE", error.code)
        assertEquals(ErrorState.Kind.CAPABILITY, error.kind)
        assertTrue(error.retryable)
        assertEquals("req_1", error.requestId)
    }

    @Test
    fun createAndGetJobUsePrivateApiContract() {
        val requests = mutableListOf<String>()
        val interceptor = Interceptor { chain ->
            val request = chain.request()
            requests += "${request.method} ${request.url} ${request.header("Authorization")} ${request.body?.contentType()}"
            val body = if (request.method == "POST") {
                "{\"id\":\"proc_1\",\"job_id\":\"proc_1\",\"status\":\"queued\",\"state\":\"QUEUED\",\"correlation_id\":\"cor_1\"}"
            } else {
                "{\"id\":\"proc_1\",\"status\":\"done\",\"state\":\"COMPLETED\",\"progress\":1,\"results\":{\"render\":{\"outputs\":[{\"path\":\"/v1/processing/jobs/proc_1/media/clip.mp4\",\"start\":1,\"end\":4}]}}}"
            }
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        val http = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val context = RuntimeEnvironment.getApplication()
        val client = ApiClient(context.contentResolver, http)
        val config = GatewayConfig("https://private.example", "session-token")

        val created = kotlinx.coroutines.runBlocking {
            client.createJob(config, ApiClient.RenderRequest("https://source.example/video.mp4", idempotencyKey = "idem_1")).getOrThrow()
        }
        val completed = kotlinx.coroutines.runBlocking { client.getJob(config, created.id).getOrThrow() }

        assertEquals("proc_1", created.id)
        assertEquals(JobState.COMPLETED, completed.state)
        assertEquals(1, completed.outputs.single().startTimeSec)
        assertEquals("https://private.example/v1/processing/jobs/proc_1/media/clip.mp4", completed.outputs.single().mediaUrl)
        assertTrue(requests.first().contains("Bearer session-token"))
        assertTrue(requests.first().contains("application/json"))
        assertTrue(requests.first().contains("/v1/processing/jobs"))
    }
}
