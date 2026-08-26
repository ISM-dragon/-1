package com.example

import com.example.data.engine.ProcessingEngine
import com.example.data.model.GatewayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingEngineTest {
    private val engine = ProcessingEngine()

    @Test
    fun `blank gateway is rejected before work is scheduled`() {
        val result = engine.plan(
            "content://media/video/42",
            GatewayConfig(token = "secret")
        )

        assertTrue(result.isFailure)
        assertEquals(
            "يجب ضبط عنوان Gateway الخاص قبل جدولة المعالجة.",
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun `blank gateway token is rejected before work is scheduled`() {
        val result = engine.plan(
            "content://media/video/42",
            GatewayConfig(baseUrl = "https://gateway.example.com")
        )

        assertTrue(result.isFailure)
        assertEquals(
            "يجب ضبط رمز Gateway قبل جدولة المعالجة.",
            result.exceptionOrNull()?.message
        )
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
    fun `remote https source routes to gateway`() {
        val result = engine.plan(
            "https://www.youtube.com/watch?v=example",
            GatewayConfig(baseUrl = "https://gateway.example.com", token = "secret")
        )

        assertTrue(result.isSuccess)
        assertEquals(ProcessingEngine.Route.REMOTE_GATEWAY, result.getOrThrow().route)
    }

    @Test
    fun `remote source is rejected without a gateway`() {
        val result = engine.plan("https://example.com/video.mp4", GatewayConfig())

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
