package com.example.core.model

/**
 * Authoritative processing state exposed by the private Gateway.
 * The app never derives progress from elapsed time; it mirrors this state.
 */
enum class JobState {
    QUEUED,
    PREPARING,
    DOWNLOADING,
    INGESTING,
    TRANSCRIBING,
    DIARIZING,
    ANALYZING,
    CANDIDATES_READY,
    SCORING,
    EDITING,
    RENDERING,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RETRY_WAIT,
    INTERRUPTED,
    UNKNOWN;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED

    val isRecoverable: Boolean
        get() = this == INTERRUPTED || this == RETRY_WAIT || this == FAILED

    val isActive: Boolean
        get() = !isTerminal && this != UNKNOWN

    companion object {
        fun fromWire(state: String?, compatibilityStatus: String? = null): JobState {
            val normalized = state.orEmpty().trim().uppercase()
            if (normalized.isNotBlank()) {
                entries.firstOrNull { it.name == normalized }?.let { return it }
            }
            return when (compatibilityStatus.orEmpty().trim().lowercase()) {
                "queued", "pending" -> QUEUED
                "running", "processing" -> RENDERING
                "done", "completed", "succeeded", "success" -> COMPLETED
                "failed", "error" -> FAILED
                "cancelled", "canceled" -> CANCELLED
                else -> UNKNOWN
            }
        }
    }
}
