package com.example

import com.example.data.engine.ProcessingEngine
import com.example.data.model.GatewayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingEngineTest {
    private val engine = ProcessingEngine()

    @Test
    fun `local media uses the on-device pipeline without a gateway`() {
        val result = engine.plan(
            "content://media/video/42",
            GatewayConfig()
        )

        assertTrue(result.isSuccess)
        assertEquals(ProcessingEngine.Route.LOCAL_ON_DEVICE, result.getOrThrow().route)
    }

    @Test
    fun `valid private gateway routes a local file remotely`() {
        val result = engine.plan(
            "file:///data/user/0/com.aistudio.opuspro.apk/files/input.mp4",
            GatewayConfig(baseUrl = "https://gateway.example.com/", token = "secret")
        )

        assertTrue(result.exceptionOrNull()?.message.orEmpty(), result.isSuccess)
        assertEquals(ProcessingEngine.Route.REMOTE_GATEWAY, result.getOrThrow().route)
        assertEquals("https://gateway.example.com", result.getOrThrow().gatewayUrl)
    }

    @Test
    fun `invalid source is rejected before work is scheduled`() {
        val result = engine.plan(
            "https:///broken-video.mp4",
            GatewayConfig(baseUrl = "https://gateway.example.com", token = "secret")
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `missing gateway token is rejected`() {
        val result = engine.plan(
            "content://media/video/42",
            GatewayConfig(baseUrl = "https://gateway.example.com")
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `invalid gateway address is rejected`() {
        val result = engine.plan(
            "content://media/video/42",
            GatewayConfig(baseUrl = "gateway-without-scheme", token = "secret")
        )

        assertTrue(result.isFailure)
    }
}
