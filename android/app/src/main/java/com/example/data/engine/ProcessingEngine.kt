package com.example.data.engine

import java.net.URI
import com.example.data.model.GatewayConfig

/**
 * The mobile processing engine's routing boundary.
 *
 * The UI submits a job; this class decides which production executor should own it.
 * It intentionally contains no local fallback: every production route goes through
 * the private Gateway, which may receive a local file or fetch an HTTP/HTTPS source.
 */
class ProcessingEngine {
    enum class Route {
        REMOTE_GATEWAY
    }

    data class Plan(
        val route: Route,
        val sourceUri: String,
        val gatewayUrl: String,
        val label: String
    )

    fun plan(sourceUri: String, gateway: GatewayConfig): Result<Plan> {
        val trimmedSource = sourceUri.trim()
        if (trimmedSource.isBlank()) {
            return Result.failure(IllegalArgumentException("مصدر الفيديو مطلوب."))
        }

        val parsedSource = runCatching { URI(trimmedSource) }.getOrNull()
        val scheme = parsedSource?.scheme?.lowercase()
        val isLocalSource = scheme in setOf("content", "file")
        val isRemoteSource = scheme in setOf("http", "https") && !parsedSource?.host.isNullOrBlank()
        if (!isLocalSource && !isRemoteSource) {
            return Result.failure(
                IllegalArgumentException("مصدر الفيديو يجب أن يكون content:// أو file:// محليًا، أو HTTP/HTTPS عند استخدام Gateway.")
            )
        }

        val baseUrl = gateway.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            return Result.failure(
                IllegalArgumentException("يجب ضبط عنوان Gateway الخاص قبل جدولة المعالجة.")
            )
        }
        if (gateway.token.trim().isBlank()) {
            return Result.failure(
                IllegalArgumentException("يجب ضبط رمز Gateway قبل جدولة المعالجة.")
            )
        }

        val parsedGateway = runCatching { URI(baseUrl) }.getOrNull()
        val gatewayScheme = parsedGateway?.scheme?.lowercase()
        if (gatewayScheme == null || gatewayScheme !in setOf("http", "https") || parsedGateway.host.isNullOrBlank()) {
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
