package com.example.core.security

import android.content.Context
import com.example.data.model.GatewayConfig
import com.example.domain.security.SecureKeyManager
import java.net.URI

/** Configuration for the private Gateway; the token is encrypted at rest with Android Keystore. */
data class PrivateBackendConfig(
    val baseUrl: String = "",
    val sessionToken: String = ""
) {
    fun asGatewayConfig(): GatewayConfig = GatewayConfig(baseUrl = baseUrl, token = sessionToken)
}

class PrivateBackendConfigStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val keyManager = SecureKeyManager(context.applicationContext)
    private val legacyPreferences = context.applicationContext.getSharedPreferences(LEGACY_PREFERENCES, Context.MODE_PRIVATE)

    init {
        migrateLegacyConfiguration()
    }

    fun load(): PrivateBackendConfig = PrivateBackendConfig(
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty().trim(),
        sessionToken = readEncryptedToken()
    )

    fun save(config: PrivateBackendConfig) {
        val normalizedUrl = validateBaseUrl(config.baseUrl)
        val editor = preferences.edit().putString(KEY_BASE_URL, normalizedUrl)
        if (config.sessionToken.isBlank()) {
            editor.remove(KEY_TOKEN_ENCRYPTED).remove(KEY_TOKEN_LEGACY)
        } else {
            editor.putString(KEY_TOKEN_ENCRYPTED, keyManager.encrypt(config.sessionToken.trim())).remove(KEY_TOKEN_LEGACY)
        }
        editor.apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_BASE_URL).remove(KEY_TOKEN_ENCRYPTED).remove(KEY_TOKEN_LEGACY).apply()
    }

    private fun migrateLegacyConfiguration() {
        if (preferences.getString(KEY_BASE_URL, "").orEmpty().isNotBlank()) return
        val legacyUrl = legacyPreferences.getString("base_url", "").orEmpty().trim()
        if (legacyUrl.isBlank()) return
        val legacyEncrypted = legacyPreferences.getString("gateway_token_encrypted", "").orEmpty()
        val legacyPlain = legacyPreferences.getString("gateway_token", "").orEmpty()
        val legacyToken = if (legacyEncrypted.isNotBlank()) keyManager.decrypt(legacyEncrypted) else legacyPlain
        val editor = preferences.edit().putString(KEY_BASE_URL, legacyUrl)
        if (legacyToken.isNotBlank()) editor.putString(KEY_TOKEN_ENCRYPTED, keyManager.encrypt(legacyToken))
        editor.apply()
        legacyPreferences.edit().remove("gateway_token").remove("gateway_token_encrypted").apply()
    }

    private fun readEncryptedToken(): String {
        val protectedToken = preferences.getString(KEY_TOKEN_ENCRYPTED, "").orEmpty()
        if (protectedToken.isNotBlank()) return runCatching { keyManager.decrypt(protectedToken) }.getOrDefault("")
        val legacy = preferences.getString(KEY_TOKEN_LEGACY, "").orEmpty()
        if (legacy.isNotBlank()) {
            runCatching {
                preferences.edit()
                    .putString(KEY_TOKEN_ENCRYPTED, keyManager.encrypt(legacy))
                    .remove(KEY_TOKEN_LEGACY)
                    .apply()
            }
        }
        return legacy
    }

    private fun validateBaseUrl(raw: String): String {
        val normalized = raw.trim().removeSuffix("/")
        require(normalized.isNotBlank()) { "عنوان الـGateway غير مضبوط" }
        val uri = URI(normalized)
        val host = uri.host?.lowercase().orEmpty()
        val local = host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("10.") || host.startsWith("192.168.") ||
            (host.startsWith("172.") && host.split('.').getOrNull(1)?.toIntOrNull() in 16..31)
        require(uri.scheme?.lowercase() == "https" || (uri.scheme?.lowercase() == "http" && local)) {
            "يجب استخدام HTTPS خارج الشبكة المحلية"
        }
        require(uri.userInfo == null) { "بيانات اعتماد URL غير مسموحة" }
        return normalized
    }

    companion object {
        private const val PREFERENCES = "ism_private_backend"
        private const val LEGACY_PREFERENCES = "ism_gateway_settings"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN_ENCRYPTED = "session_token_encrypted"
        private const val KEY_TOKEN_LEGACY = "session_token"
    }
}
