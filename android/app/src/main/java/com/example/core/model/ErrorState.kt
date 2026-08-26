package com.example.core.model

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/** Safe, stable error model. Raw response bodies and credentials are never exposed. */
class ApiException(val error: ErrorState) : IOException(error.message)

data class ErrorState(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val kind: Kind,
    val requestId: String? = null,
    val cause: Throwable? = null
) {
    enum class Kind {
        AUTHENTICATION,
        CAPABILITY,
        NETWORK,
        VALIDATION,
        TERMINAL_JOB,
        OFFLINE,
        UNKNOWN
    }

    companion object {
        fun fromHttp(
            statusCode: Int,
            code: String?,
            message: String?,
            retryable: Boolean? = null,
            requestId: String? = null
        ): ErrorState {
            val safeCode = code.orEmpty().trim().ifBlank { "HTTP_$statusCode" }
            val kind = when {
                statusCode == 401 || statusCode == 403 -> Kind.AUTHENTICATION
                statusCode == 400 || statusCode == 409 || statusCode == 422 -> Kind.VALIDATION
                statusCode == 429 || statusCode >= 500 -> Kind.CAPABILITY
                else -> Kind.UNKNOWN
            }
            val canRetry = retryable ?: statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500
            return ErrorState(
                code = safeCode,
                message = message.orEmpty().trim().ifBlank { "تعذر تنفيذ طلب الـGateway." },
                retryable = canRetry,
                kind = kind,
                requestId = requestId
            )
        }

        fun fromThrowable(error: Throwable): ErrorState {
            val root = generateSequence(error) { it.cause }.last()
            val network = root is IOException || root is ConnectException || root is SocketTimeoutException
            return ErrorState(
                code = if (network) "NETWORK_UNAVAILABLE" else "ANDROID_CLIENT_ERROR",
                message = if (network) "الاتصال بالـGateway غير متاح حاليًا." else (error.message ?: "تعذر تنفيذ الطلب."),
                retryable = network,
                kind = if (network) Kind.NETWORK else Kind.UNKNOWN,
                cause = error
            )
        }

        fun offline(): ErrorState = ErrorState(
            code = "OFFLINE",
            message = "لا يوجد اتصال بالشبكة. ستستمر المهمة عند عودة الاتصال.",
            retryable = true,
            kind = Kind.OFFLINE
        )

        fun terminalJob(code: String, message: String, recoverable: Boolean, requestId: String? = null): ErrorState =
            ErrorState(code, message, recoverable, Kind.TERMINAL_JOB, requestId)
    }
}
