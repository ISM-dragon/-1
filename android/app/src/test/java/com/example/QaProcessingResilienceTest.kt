package com.example

import com.example.data.engine.ProcessingEngine
import com.example.data.model.GatewayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QaProcessingResilienceTest {
    private val engine = ProcessingEngine()

    @Test
    fun validRemoteVideoUsesGatewayWithoutEmbeddingTheGatewayTokenInThePlan() {
        val plan = engine.plan(
            "https://cdn.example.test/video.mp4",
            GatewayConfig("https://gateway.example.test/", "private-session-token")
        ).getOrThrow()

        assertEquals(ProcessingEngine.Route.REMOTE_GATEWAY, plan.route)
        assertEquals("https://gateway.example.test", plan.gatewayUrl)
        assertFalse(plan.gatewayUrl.orEmpty().contains("private-session-token"))
        assertFalse(plan.sourceUri.contains("private-session-token"))
    }

    @Test
    fun brokenRemoteVideoUrlIsRejectedBeforeScheduling() {
        val result = engine.plan("https:///broken-video.mp4", GatewayConfig("https://gateway.example.test", "token"))
        assertTrue(result.isFailure)
    }

    @Test
    fun remoteVideoWithoutGatewayIsRejectedWithoutLocalFallback() {
        val result = engine.plan("https://cdn.example.test/video.mp4", GatewayConfig())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Gateway"))
    }

    @Test
    fun malformedGatewayCannotBeUsedAfterNetworkInterruptionOrRestart() {
        val result = engine.plan("https://cdn.example.test/video.mp4", GatewayConfig("file:///tmp/gateway", "token"))
        assertTrue(result.isFailure)
    }

    @Test
    fun localFileCanUseRemoteGatewayForUploadAndDoesNotExposeToken() {
        val plan = engine.plan(
            "content://media/external/video/42",
            GatewayConfig("http://192.168.1.10:8787", "lan-token")
        ).getOrThrow()

        assertEquals(ProcessingEngine.Route.REMOTE_GATEWAY, plan.route)
        assertFalse(plan.sourceUri.contains("lan-token"))
        assertFalse(plan.gatewayUrl.orEmpty().contains("lan-token"))
    }
}
