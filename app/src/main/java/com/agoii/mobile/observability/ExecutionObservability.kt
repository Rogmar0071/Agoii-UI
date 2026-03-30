package com.agoii.mobile.observability

import com.agoii.mobile.core.*

class ExecutionObservability(
    private val ledger: EventLedger
) {

    fun trace(projectId: String): ExecutionTrace {

        val events = ledger.loadEvents(projectId)

        val steps = mutableListOf<ExecutionStep>()

        events.forEach { event ->

            when (event.type) {

                EventTypes.TASK_STARTED -> {
                    val taskId = event.payload["taskId"]?.toString() ?: ""

                    steps.add(
                        ExecutionStep(
                            taskId = taskId,
                            status = "STARTED",
                            artifactKeys = emptyList(),
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }

                EventTypes.EXECUTION_COMPLETED -> {
                    val taskId = event.payload["taskId"]?.toString() ?: ""

                    val artifactKeys =
                        (event.payload["artifact"] as? Map<*, *>)?.keys
                            ?.map { it.toString() }
                            ?: emptyList()

                    steps.add(
                        ExecutionStep(
                            taskId = taskId,
                            status = "COMPLETED",
                            artifactKeys = artifactKeys,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }

        return ExecutionTrace(projectId, steps)
    }
}
