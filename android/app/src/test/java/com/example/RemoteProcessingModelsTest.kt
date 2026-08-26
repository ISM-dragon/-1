package com.example

import com.example.remote.model.GatewayJobState
import com.example.remote.model.LocalProcessingJob
import com.example.remote.model.RemoteClip
import com.example.remote.model.GatewayJob
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemoteProcessingModelsTest {
    @Test
    fun localJobRoundTripPreservesRemoteIdentityAndDownloadedResults() {
        val original = LocalProcessingJob(
            localId = "local-1",
            title = "Interview",
            sourceUri = "content://media/video/1",
            uploadedSourceUrl = "https://gateway.example/v1/sources/jobs/upl_1/media/source.mp4",
            remoteJobId = "proc_1",
            state = GatewayJobState.COMPLETED,
            progress = 100,
            stage = "FINALIZING",
            message = "done",
            recoverable = false,
            clips = listOf(
                RemoteClip("clip-1", "Hook", 3, 12, 9, 94, "hello", "https://gateway.example/clip.mp4", "/data/data/ism/clip.mp4")
            ),
            idempotencyKey = "stable-key"
        )

        val restored = LocalProcessingJob.fromJson(JSONObject(original.toJson().toString()))

        assertEquals(original.localId, restored.localId)
        assertEquals(original.remoteJobId, restored.remoteJobId)
        assertEquals(original.idempotencyKey, restored.idempotencyKey)
        assertEquals(GatewayJobState.COMPLETED, restored.state)
        assertEquals("clip-1", restored.clips.single().id)
        assertEquals("/data/data/ism/clip.mp4", restored.clips.single().localPath)
        assertTrue(!restored.isActive)
    }

    @Test
    fun gatewayParserReadsCanonicalProgressAndArtifacts() {
        val json = JSONObject(
            """
            {
              "job_id":"proc_9",
              "state":"RENDERING",
              "progress":0.48,
              "stage":"render",
              "retry_count":1,
              "recoverable":true,
              "results":{"render":{"outputs":[{"id":"c9","title":"Cut","start":1,"end":6,"duration":5,"score":88,"path":"https://gateway.example/cut.mp4"}]}}
            }
            """.trimIndent()
        )

        val job = GatewayJob.fromJson(json)

        assertEquals("proc_9", job.id)
        assertEquals(GatewayJobState.RENDERING, job.state)
        assertEquals(48, job.progress)
        assertEquals(1, job.retryCount)
        assertTrue(job.recoverable)
        assertEquals("c9", job.clips.single().id)
        assertEquals(88, job.clips.single().score)
    }
}
