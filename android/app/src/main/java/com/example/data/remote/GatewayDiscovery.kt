package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.GatewayConfig
import com.example.data.model.AppError
import com.example.data.model.AppResult
import com.example.data.repository.ContractJobRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * P0: Gateway Auto-Discovery - No manual URL entry required
 * 
 * User says: "لن احتاج ان ادخل رابط هناك او ايت شي متعلق به"
 * Solution: Auto-discover gateway via:
 * 1. Saved config (if exists)
 * 2. BuildConfig default URL (from env ISM_GATEWAY_URL)
 * 3. Fallback URLs (emulator, local network)
 * 4. QR/Deep-link (future)
 */
class GatewayDiscovery(private val context: Context) {
    
    companion object {
        private const val TAG = "GatewayDiscovery"
        
        // Free gateways - no manual entry, no server setup needed! 🆓
        private val FREE_GATEWAYS = listOf(
            "https://ism-free-gateway.fly.dev",           // Fly.io free tier - recommended
            "https://ism-free-gateway.onrender.com",      // Render free tier
            "https://ism-gateway-free.hf.space",          // HuggingFace Spaces
            "https://ism-free.up.railway.app"             // Railway free
        )
        
        // Default production URLs - no manual entry needed
        private val DEFAULT_PRODUCTION_URLS = listOf(
            "https://gateway.ism.local",
            "https://api.ism.app",
            "https://ism-gateway.fly.dev",
            "https://ism-free-gateway.fly.dev",           // Free gateway as production fallback
            "https://ism-free-gateway.onrender.com"
        )
        
        // Local development URLs
        private val LOCAL_URLS = listOf(
            "http://10.0.2.2:8787",      // Android emulator localhost
            "http://10.0.3.2:8787",      // Genymotion
            "http://192.168.1.100:8787", // Common local network
            "http://192.168.1.1:8787",
            "http://127.0.0.1:8787"       // Direct localhost (for testing)
        )
    }
    
    /**
     * Get gateway config with auto-discovery - no manual entry required
     */
    suspend fun getOrDiscoverConfig(repository: ContractJobRepository): AppResult<GatewayConfig> = withContext(Dispatchers.IO) {
        try {
            // 1. Try saved config first
            val saved = repository.loadGatewayConfig()
            if (saved.baseUrl.isNotBlank()) {
                Log.d(TAG, "Using saved gateway config: ${saved.baseUrl}")
                if (isGatewayHealthy(saved)) {
                    return@withContext AppResult.success(saved)
                }
                Log.w(TAG, "Saved gateway not healthy, trying auto-discovery")
            }
            
            // 2. Try BuildConfig defaults (user configured)
            val buildConfigUrls = getBuildConfigUrls()
            for (url in buildConfigUrls) {
                val config = GatewayConfig(baseUrl = url, token = saved.token)
                if (isGatewayHealthy(config)) {
                    Log.i(TAG, "Auto-discovered gateway via BuildConfig: $url")
                    // Auto-save discovered config (no manual entry!)
                    repository.saveGatewayConfig(config)
                    return@withContext AppResult.success(config)
                }
            }
            
            // 3. Try FREE gateways first (no setup needed! 🆓) - PRIORITY for v1
            if (BuildConfig.GATEWAY_AUTO_DISCOVERY) {
                Log.i(TAG, "Trying FREE gateways (no setup needed)...")
                for (url in FREE_GATEWAYS) {
                    val config = GatewayConfig(baseUrl = url, token = "") // Free gateways don't need token
                    if (isGatewayHealthy(config)) {
                        Log.i(TAG, "✅ Auto-discovered FREE gateway: $url - No manual setup needed!")
                        repository.saveGatewayConfig(config)
                        return@withContext AppResult.success(config)
                    }
                }
                
                // 4. Try production defaults
                for (url in DEFAULT_PRODUCTION_URLS) {
                    val config = GatewayConfig(baseUrl = url, token = saved.token)
                    if (isGatewayHealthy(config)) {
                        Log.i(TAG, "Auto-discovered gateway via production defaults: $url")
                        repository.saveGatewayConfig(config)
                        return@withContext AppResult.success(config)
                    }
                }
                
                // 5. Try local network (for development)
                for (url in LOCAL_URLS) {
                    val config = GatewayConfig(baseUrl = url, token = saved.token)
                    if (isGatewayHealthy(config)) {
                        Log.i(TAG, "Auto-discovered gateway via local network: $url")
                        repository.saveGatewayConfig(config)
                        return@withContext AppResult.success(config)
                    }
                }
            }
            
            // 6. If no gateway found but we have a default, return it anyway (will fail gracefully with proper error)
            val defaultUrl = BuildConfig.GATEWAY_DEFAULT_URL
            if (defaultUrl.isNotBlank()) {
                Log.w(TAG, "No healthy gateway found, returning default: $defaultUrl")
                return@withContext AppResult.success(GatewayConfig(baseUrl = defaultUrl, token = saved.token))
            }
            
            // 7. Ultimate fallback - FREE gateway even if not healthy (better than error, will wake up)
            // Free gateways on Render/Fly may sleep, but return them anyway - they wake on request
            val freeFallback = FREE_GATEWAYS.first()
            Log.w(TAG, "No healthy gateway found, returning FREE fallback: $freeFallback (may need to wake up)")
            repository.saveGatewayConfig(GatewayConfig(baseUrl = freeFallback, token = ""))
            return@withContext AppResult.success(GatewayConfig(baseUrl = freeFallback, token = ""))
            
            // 8. Ultimate ultimate fallback - return saved config even if not healthy, UI will show error
            // This is now unreachable due to free fallback above, but kept for logic
            // if (saved.baseUrl.isNotBlank()) {
            //     return@withContext AppResult.success(saved)
            // }
            // AppResult.error(
            //     AppError.NetworkError("تعذر اكتشاف Gateway تلقائياً. تحقق من الاتصال أو أدخل الرابط يدوياً في الإعدادات المتقدمة.")
            // )
        } catch (e: Exception) {
            Log.e(TAG, "Gateway discovery failed", e)
            AppResult.error(AppError.NetworkError("فشل اكتشاف Gateway: ${e.message}"))
        }
    }
    
    private fun getBuildConfigUrls(): List<String> {
        val urls = mutableListOf<String>()
        
        // Primary from BuildConfig
        val primary = BuildConfig.GATEWAY_DEFAULT_URL
        if (primary.isNotBlank()) {
            urls.add(primary)
        }
        
        // Fallbacks from BuildConfig
        val fallbacks = BuildConfig.GATEWAY_FALLBACK_URLS
        if (fallbacks.isNotBlank()) {
            urls.addAll(fallbacks.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }
        
        return urls.distinct()
    }
    
    private fun isGatewayHealthy(config: GatewayConfig): Boolean {
        if (config.baseUrl.isBlank()) return false
        
        return try {
            val url = "${config.baseUrl.trim().removeSuffix("/")}/health"
            val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 5000
                setRequestProperty("Accept", "application/json")
                if (config.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.token.trim()}")
                }
            }
            
            try {
                val code = connection.responseCode
                if (code !in 200..299) return false
                
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                // Check if gateway reports ok or degraded (degraded is still healthy enough)
                json.optBoolean("ok", false) || json.optString("status") in listOf("ok", "degraded")
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Gateway health check failed for ${config.baseUrl}: ${e.message}")
            false
        }
    }
    
    /**
     * For QR code / deep-link gateway setup (future)
     * User can scan QR that contains gateway URL + token
     */
    fun parseGatewayFromDeepLink(deepLink: String): GatewayConfig? {
        return try {
            val uri = URI(deepLink)
            // Example: ism://gateway?url=https://gateway.example.com&token=xxx
            // Or: https://ism.app/setup?gateway_url=...&token=...
            val query = uri.query ?: return null
            val params = query.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (parts.getOrNull(1) ?: "")
            }
            
            val url = params["url"] ?: params["gateway_url"] ?: params["base_url"] ?: return null
            val token = params["token"] ?: params["gateway_token"] ?: ""
            
            GatewayConfig(baseUrl = url, token = token)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse gateway deep link", e)
            null
        }
    }
}
