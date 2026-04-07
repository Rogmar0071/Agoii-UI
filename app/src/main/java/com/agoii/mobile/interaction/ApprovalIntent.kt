package com.agoii.mobile.interaction

data class ApprovalIntent(
    val intentId: String,
    val action: ApprovalAction
)

enum class ApprovalAction {
    APPROVE,
    REJECT
}
