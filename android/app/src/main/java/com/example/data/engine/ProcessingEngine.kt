package com.example.data.engine

import android.net.Uri
import com.example.data.model.GatewayConfig

/** Contract boundary: Android orchestrates a remote Gateway and never runs the pipeline locally. */
class ProcessingEngine {
    enum class Route { REMOTE_GATEWAY }

    data class Plan(
        val route: Route,
        val sourceUri: String,
        val gatewayUrl: String,
        val label: String = "Gateway API"
    )

    fun plan(sourceUri: String, gateway: GatewayConfig): Result<Plan> {
        val trimmedSource = sourceUri.trim()
        if (trimmedSource.isBlank()) return Result.failure(IllegalArgumentException("مصدر الفيديو مطلوب."))
        val scheme = runCatching { java.net.URI(trimmedSource).scheme?.lowercase() }.getOrNull()
        if (scheme == null || scheme !in setOf("content", "file")) {
            return Result.failure(IllegalArgumentException("مصدر الفيديو يجب أن يكون ملفًا محليًا قابلًا للقراءة."))
        }
        val baseUrl = gateway.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return Result.failure(IllegalArgumentException("عنوان Gateway مطلوب."))
        val parsed = runCatching { java.net.URI(baseUrl) }.getOrNull()
        if (parsed?.scheme !in setOf("http", "https") || parsed?.host.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("عنوان Gateway غير صالح."))
        }
        return Result.success(Plan(Route.REMOTE_GATEWAY, trimmedSource, baseUrl))
    }
}
