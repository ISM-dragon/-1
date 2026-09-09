package com.example.data.model

/**
 * P0: Unified Error Model
 * Machine-readable, user-readable, loggable without secrets, recoverable when possible
 */
sealed class AppError(
    open val code: String,
    open val userMessage: String,
    open val technicalMessage: String? = null,
    open val recoverable: Boolean = true,
    open val retryAfterSeconds: Int? = null
) {
    // Network errors
    data class NetworkError(
        override val userMessage: String = "تعذر الاتصال بالخادم. تحقق من الاتصال بالإنترنت.",
        override val technicalMessage: String? = null
    ) : AppError("NETWORK_ERROR", userMessage, technicalMessage, true)
    
    data class TimeoutError(
        override val userMessage: String = "انتهت مهلة الاتصال. حاول مرة أخرى.",
        override val technicalMessage: String? = null
    ) : AppError("TIMEOUT_ERROR", userMessage, technicalMessage, true)
    
    // Auth errors
    data class AuthError(
        override val userMessage: String = "فشلت المصادقة. تحقق من إعدادات Gateway.",
        override val technicalMessage: String? = null
    ) : AppError("AUTH_ERROR", userMessage, technicalMessage, false)
    
    data class DeviceMismatchError(
        override val userMessage: String = "هذا الجهاز غير مصرح له. الجهاز مرتبط بجهاز آخر.",
        override val technicalMessage: String? = null
    ) : AppError("DEVICE_MISMATCH", userMessage, technicalMessage, false)
    
    // OAuth errors
    data class OAuthError(
        override val code: String = "OAUTH_ERROR",
        override val userMessage: String = "فشل تسجيل الدخول. حاول مرة أخرى.",
        override val technicalMessage: String? = null,
        val provider: String? = null
    ) : AppError(code, userMessage, technicalMessage, true)
    
    data class OAuthStateError(
        override val userMessage: String = "انتهت صلاحية جلسة تسجيل الدخول أو تم التلاعب بها."
    ) : AppError("OAUTH_STATE_INVALID", userMessage, null, true)
    
    // Validation errors
    data class ValidationError(
        override val userMessage: String,
        override val technicalMessage: String? = null,
        val field: String? = null
    ) : AppError("VALIDATION_ERROR", userMessage, technicalMessage, false)
    
    // Upload errors
    data class UploadError(
        override val code: String = "UPLOAD_ERROR",
        override val userMessage: String = "فشل رفع الفيديو.",
        override val technicalMessage: String? = null,
        override val recoverable: Boolean = true
    ) : AppError(code, userMessage, technicalMessage, recoverable)
    
    data class UploadTooLargeError(
        val maxBytes: Long,
        val actualBytes: Long
    ) : AppError(
        "UPLOAD_TOO_LARGE",
        "حجم الفيديو كبير جداً. الحد الأقصى ${maxBytes / (1024*1024)}MB",
        "max=$maxBytes actual=$actualBytes",
        false
    )
    
    data class UnsupportedFormatError(
        val mimeType: String
    ) : AppError(
        "UNSUPPORTED_FORMAT",
        "صيغة الفيديو غير مدعومة: $mimeType",
        null,
        false
    )
    
    // Processing errors
    data class ProcessingError(
        override val code: String = "PROCESSING_ERROR",
        override val userMessage: String = "فشلت معالجة الفيديو.",
        override val technicalMessage: String? = null,
        override val recoverable: Boolean = true
    ) : AppError(code, userMessage, technicalMessage, recoverable)
    
    data class JobNotFoundError(
        val jobId: String
    ) : AppError(
        "JOB_NOT_FOUND",
        "المهمة غير موجودة.",
        "jobId=$jobId",
        false
    )
    
    data class JobNotResumableError(
        val jobId: String
    ) : AppError(
        "JOB_NOT_RESUMABLE",
        "لا يمكن استئناف هذه المهمة.",
        "jobId=$jobId",
        false
    )
    
    // Provider errors
    data class ProviderError(
        override val code: String = "PROVIDER_ERROR",
        override val userMessage: String = "فشل النشر على المنصة.",
        override val technicalMessage: String? = null,
        val provider: String? = null,
        override val recoverable: Boolean = true
    ) : AppError(code, userMessage, technicalMessage, recoverable)
    
    data class RateLimitError(
        override val retryAfterSeconds: Int? = null,
        val provider: String? = null
    ) : AppError(
        "RATE_LIMIT",
        "تم تجاوز الحد المسموح. حاول بعد ${retryAfterSeconds ?: 60} ثانية.",
        "provider=$provider retryAfter=$retryAfterSeconds",
        true,
        retryAfterSeconds
    )
    
    // Server errors
    data class ServerError(
        override val code: String = "SERVER_ERROR",
        override val userMessage: String = "خطأ في الخادم. حاول مرة أخرى لاحقاً.",
        override val technicalMessage: String? = null
    ) : AppError(code, userMessage, technicalMessage, true)
    
    data class StorageError(
        override val userMessage: String = "مساحة التخزين غير كافية."
    ) : AppError("STORAGE_ERROR", userMessage, null, false)
    
    // Unknown
    data class UnknownError(
        override val userMessage: String = "حدث خطأ غير متوقع.",
        override val technicalMessage: String? = null
    ) : AppError("UNKNOWN_ERROR", userMessage, technicalMessage, true)
    
    /**
     * User-readable message without secrets
     */
    fun toUserMessage(): String = userMessage
    
    /**
     * Loggable without secrets (redacted)
     */
    fun toLogMessage(): String = "[$code] $technicalMessage (recoverable=$recoverable)"
    
    companion object {
        fun fromCode(code: String?, message: String?, provider: String? = null): AppError {
            return when (code?.uppercase()) {
                "NETWORK_ERROR", "NETWORK_UNREACHABLE" -> NetworkError(message ?: "تعذر الاتصال")
                "TIMEOUT", "TIMEOUT_ERROR" -> TimeoutError(message ?: "انتهت المهلة")
                "UNAUTHORIZED", "AUTH_ERROR", "AUTH_FAILED" -> AuthError(message ?: "فشلت المصادقة")
                "DEVICE_MISMATCH" -> DeviceMismatchError()
                "OAUTH_ERROR", "OAUTH_FAILED" -> OAuthError(userMessage = message ?: "فشل OAuth", provider = provider)
                "OAUTH_STATE_INVALID", "OAUTH_STATE_MISMATCH", "CSRF_ERROR" -> OAuthStateError()
                "VALIDATION_ERROR" -> ValidationError(message ?: "بيانات غير صالحة")
                "UPLOAD_ERROR" -> UploadError(userMessage = message ?: "فشل الرفع")
                "UPLOAD_TOO_LARGE" -> UploadError(code = "UPLOAD_TOO_LARGE", userMessage = message ?: "حجم كبير")
                "UNSUPPORTED_FORMAT", "UNSUPPORTED_VIDEO" -> UploadError(code = "UNSUPPORTED_FORMAT", userMessage = message ?: "صيغة غير مدعومة")
                "JOB_NOT_FOUND" -> JobNotFoundError(jobId = "")
                "JOB_NOT_RESUMABLE", "CHECKPOINT_NOT_FOUND" -> JobNotResumableError(jobId = "")
                "PROVIDER_ERROR", "PUBLISH_FAILED" -> ProviderError(userMessage = message ?: "فشل النشر", provider = provider)
                "RATE_LIMIT", "RATE_LIMITED", "429" -> RateLimitError(provider = provider)
                "STORAGE_ERROR", "STORAGE_UNAVAILABLE", "LOW_DISK" -> StorageError()
                "SERVER_ERROR", "500", "502", "503", "504" -> ServerError(technicalMessage = message)
                else -> if (message != null) UnknownError(message) else UnknownError()
            }
        }
        
        fun fromHttpCode(httpCode: Int, body: String?, provider: String? = null): AppError {
            return when (httpCode) {
                400, 422 -> ValidationError(body ?: "بيانات غير صالحة")
                401, 403 -> AuthError(body ?: "غير مصرح")
                404 -> JobNotFoundError(jobId = "")
                409 -> ProcessingError(code = "CONFLICT", userMessage = body ?: "تعارض في الحالة", recoverable = true)
                413 -> UploadError(code = "UPLOAD_TOO_LARGE", userMessage = body ?: "حجم كبير")
                429 -> RateLimitError(provider = provider)
                in 500..599 -> ServerError(technicalMessage = body)
                else -> UnknownError(body ?: "HTTP $httpCode")
            }
        }
    }
}

/**
 * Result wrapper with error
 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val error: AppError) : AppResult<Nothing>()
    
    fun isSuccess(): Boolean = this is Success
    fun isError(): Boolean = this is Error
    
    fun getOrNull(): T? = when (this) {
        is Success -> data
        is Error -> null
    }
    
    fun errorOrNull(): AppError? = when (this) {
        is Success -> null
        is Error -> error
    }
    
    companion object {
        fun <T> success(data: T): AppResult<T> = Success(data)
        fun <T> error(error: AppError): AppResult<T> = Error(error)
    }
}
