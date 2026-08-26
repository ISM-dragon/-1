package com.example.data.engine

import java.net.URI
import com.example.data.model.GatewayConfig

/**
 * The mobile processing engine's routing boundary.
 *
 * The UI submits a job; this class decides which production executor should own it.
 * It intentionally contains no fake fallback: a remote route is selected only when
 * a real Gateway URL is configured, otherwise the local pipeline is used.
 */
class ProcessingEngine {
    enum class Route {
        LOCAL_PIPELINE,
        REMOTE_GATEWAY
    }

    data class Plan(
        val route: Route,
        val sourceUri: String,
        val gatewayUrl: String? = null,
        val label: String
    )

    fun plan(sourceUri: String, gateway: GatewayConfig): Result<Plan> {
        val trimmedSource = sourceUri.trim()
        if (trimmedSource.isBlank()) {
            return Result.failure(IllegalArgumentException("مصدر الفيديو مطلوب."))
        }

        val parsedSource = runCatching { URI(trimmedSource) }.getOrNull()
        val scheme = parsedSource?.scheme?.lowercase()
        if (scheme == null || scheme !in setOf("content", "file")) {
            return Result.failure(
                IllegalArgumentException("مصدر الفيديو يجب أن يكون ملفًا محليًا قابلًا للقراءة.")
            )
        }

        val baseUrl = gateway.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return Result.success(
                Plan(
                    route = Route.LOCAL_PIPELINE,
                    sourceUri = trimmedSource,
                    label = "المحرك المحلي"
                )
            )
        }

        val parsedGateway = runCatching { URI(baseUrl) }.getOrNull()
        val gatewayScheme = parsedGateway?.scheme?.lowercase()
        if (gatewayScheme == null || gatewayScheme !in setOf("http", "https") || parsedGateway?.host.isNullOrBlank()) {
            return Result.failure(
                IllegalArgumentException("عنوان Gateway غير صالح. استخدم عنوان HTTP أو HTTPS كاملًا.")
            )
        }

        return Result.success(
            Plan(
                route = Route.REMOTE_GATEWAY,
                sourceUri = trimmedSource,
                gatewayUrl = baseUrl,
                label = "محرك Gateway البعيد"
            )
        )
    }
}
