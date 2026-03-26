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
    val failure: FailureSurface
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
    CONTRACT_STARTED,
    CONTRACT_COMPLETED,
    EXECUTION_COMPLETED
}
