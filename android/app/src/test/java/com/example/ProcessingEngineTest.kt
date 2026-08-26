package com.example

import com.example.data.engine.ProcessingEngine
import com.example.data.model.GatewayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingEngineTest {
    private val engine = ProcessingEngine()

    @Test
    fun `blank gateway routes a local file to local pipeline`() {
        val result = engine.plan("content://media/video/42", GatewayConfig())

        assertTrue(result.isSuccess)
        assertEquals(ProcessingEngine.Route.LOCAL_PIPELINE, result.getOrThrow().route)
    }

    @Test
    fun `valid gateway routes a local file to remote gateway`() {
        val result = engine.plan(
            "file:///data/user/0/com.example/files/input.mp4",
            GatewayConfig(baseUrl = "https://gateway.example.com/", token = "secret")
        )

        assertTrue(result.isSuccess)
        assertEquals(ProcessingEngine.Route.REMOTE_GATEWAY, result.getOrThrow().route)
        assertEquals("https://gateway.example.com", result.getOrThrow().gatewayUrl)
    }

    @Test
    fun `invalid source is rejected before work is scheduled`() {
        val result = engine.plan("https://example.com/video.mp4", GatewayConfig())

        assertTrue(result.isFailure)
    }

    @Test
    fun `invalid gateway address is rejected`() {
        val result = engine.plan(
            "content://media/video/42",
            GatewayConfig(baseUrl = "gateway-without-scheme")
        )

        assertTrue(result.isFailure)
    }
}
