package com.agoii.mobile.observability

enum class FailureType {
    NONE,
    INTENT_INVALID,
    CONTRACT_GENERATION_FAILED,
    AUTHORIZATION_FAILED,
    EXECUTION_BLOCKED,
    CONTRACT_FAILED,
    TASK_FAILED,
    UNKNOWN
}

data class FailureSurface(
    val type: FailureType,
    val reason: String?,
    val blockingEvent: String?
)
