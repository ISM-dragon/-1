package com.example.data.engine

import java.net.URI
import com.example.data.model.GatewayConfig

/**
 * Compatibility validation boundary for legacy callers.
 * Android processing is remote-only; the Gateway owns all media execution.
 */
class ProcessingEngine {
    enum class Route { REMOTE_GATEWAY }

    data class Plan(
        val route: Route,
        val sourceUri: String,
        val gatewayUrl: String,
        val label: String
    )

    fun plan(sourceUri: String, gateway: GatewayConfig): Result<Plan> {
        val trimmedSource = sourceUri.trim()
        if (trimmedSource.isBlank()) return Result.failure(IllegalArgumentException("مصدر الفيديو مطلوب."))
        val scheme = runCatching { URI(trimmedSource).scheme?.lowercase() }.getOrNull()
        if (scheme !in setOf("content", "file")) {
            return Result.failure(IllegalArgumentException("مصدر الفيديو يجب أن يكون ملفًا محليًا قابلًا للقراءة."))
        }

        val baseUrl = gateway.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return Result.failure(IllegalArgumentException("عنوان Gateway مطلوب؛ لا توجد معالجة محلية على Android."))
        val parsedGateway = runCatching { URI(baseUrl) }.getOrNull()
        val gatewayScheme = parsedGateway?.scheme?.lowercase()
        if (gatewayScheme !in setOf("http", "https") || parsedGateway?.host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("عنوان Gateway غير صالح. استخدم عنوان HTTP أو HTTPS كاملًا."))
        }
        return Result.success(
            Plan(
                route = Route.REMOTE_GATEWAY,
                sourceUri = trimmedSource,
                gatewayUrl = baseUrl,
                label = "Gateway البعيد"
            )
        )
    }
}
