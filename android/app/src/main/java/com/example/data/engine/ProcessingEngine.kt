package com.example.data.engine

import com.example.data.model.GatewayConfig
import java.net.URI

/**
 * Android's processing boundary. The heavy engine never runs in the APK: the
 * private Processing Gateway owns the server-side pipeline and provider keys.
 */
class ProcessingEngine {
    enum class Route {
        LOCAL_ON_DEVICE,
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
        val sourceScheme = parsedSource?.scheme?.lowercase()
        if (sourceScheme !in setOf("content", "file", "http", "https")) {
            return Result.failure(
                IllegalArgumentException("مصدر الفيديو غير صالح.")
            )
        }
        if (sourceScheme in setOf("http", "https") && parsedSource?.host.isNullOrBlank()) {
            return Result.failure(
                IllegalArgumentException("عنوان الفيديو البعيد غير صالح.")
            )
        }

        val baseUrl = gateway.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            if (sourceScheme in setOf("http", "https")) {
                return Result.failure(
                    IllegalStateException("يجب ضبط private Processing Gateway للمصادر البعيدة.")
                )
            }
            return Result.success(
                Plan(
                    route = Route.LOCAL_ON_DEVICE,
                    sourceUri = trimmedSource,
                    gatewayUrl = "",
                    label = "on-device analysis pipeline"
                )
            )
        }
        if (gateway.token.trim().isBlank()) {
            return Result.failure(
                IllegalStateException("رمز الوصول إلى private Processing Gateway مطلوب.")
            )
        }

        val parsedGateway = runCatching { URI(baseUrl) }.getOrNull()
        val gatewayScheme = parsedGateway?.scheme?.lowercase()
        val host = parsedGateway?.host.orEmpty().lowercase()
        val isLocalDevelopmentHost = host == "localhost" ||
            host == "127.0.0.1" ||
            host.startsWith("192.168.") ||
            host.startsWith("10.") ||
            host.startsWith("172.16.")
        if (parsedGateway == null || parsedGateway.host.isNullOrBlank() ||
            (gatewayScheme != "https" && !(gatewayScheme == "http" && isLocalDevelopmentHost))) {
            return Result.failure(
                IllegalArgumentException("عنوان Gateway غير صالح. استخدم HTTPS، أو HTTP على شبكة التطوير المحلية فقط.")
            )
        }

        return Result.success(
            Plan(
                route = Route.REMOTE_GATEWAY,
                sourceUri = trimmedSource,
                gatewayUrl = baseUrl,
                label = "private Processing Gateway"
            )
        )
    }
}
