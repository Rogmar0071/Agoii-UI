package com.agoii.mobile.decision

data class DecisionResult(
    val action: DecisionAction,
    val reason: String,
    val confidence: Double
)

enum class DecisionAction {
    RETRY,
    REASSIGN,
    FAIL
}

class DecisionEngine {

    fun evaluate(
        taskId: String,
        contractorId: String?,
        failureCount: Int
    ): DecisionResult {

        return when {
            failureCount == 0 -> DecisionResult(
                action = DecisionAction.RETRY,
                reason = "First failure, retry allowed",
                confidence = 0.8
            )

            failureCount in 1..2 -> DecisionResult(
                action = DecisionAction.REASSIGN,
                reason = "Repeated failure, try different contractor",
                confidence = 0.7
            )

            else -> DecisionResult(
                action = DecisionAction.FAIL,
                reason = "Failure threshold exceeded",
                confidence = 0.9
            )
        }
    }
}
