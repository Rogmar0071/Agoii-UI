package com.agoii.mobile.observability

import com.agoii.mobile.core.Event

data class ExecutionTrace(
    val projectId: String,
    val status: ExecutionStatus,
    val currentStage: ExecutionStage,
    val lastEvent: Event?,
    val totalEvents: Int,
    val contractsTotal: Int?,
    val contractsCompleted: Int?,
    val failureReason: String?,
    val failureStage: FailureStage
)

enum class ExecutionStatus {
    NOT_STARTED,
    IN_PROGRESS,
    BLOCKED,
    COMPLETED
}

enum class ExecutionStage {
    NONE,
    INTENT,
    CONTRACTS_GENERATED,
    CONTRACTS_APPROVED,
    CONTRACT_EXECUTION,
    TASK_EXECUTION,
    COMPLETED
}

enum class FailureStage {
    NONE,
    INTENT,
    CONTRACT_GENERATION,
    CONTRACT_APPROVAL,
    CONTRACT_EXECUTION,
    TASK_EXECUTION,
    UNKNOWN
}
