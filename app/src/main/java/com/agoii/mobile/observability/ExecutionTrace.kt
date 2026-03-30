package com.agoii.mobile.observability

data class ExecutionTrace(
    val projectId: String,
    val steps: List<ExecutionStep>
)

data class ExecutionStep(
    val taskId: String,
    val status: String,
    val artifactKeys: List<String>,
    val timestamp: Long
)
