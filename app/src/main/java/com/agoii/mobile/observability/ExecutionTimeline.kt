package com.agoii.mobile.observability

data class ExecutionTimeline(
    val projectId: String,
    val steps: List<TimelineStep>
)

data class TimelineStep(
    val index: Int,
    val eventType: String,
    val label: String,
    val description: String
)
