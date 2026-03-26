package com.agoii.mobile.intent

sealed class IntentResult {

    data class Accepted(
        val master: IntentMaster
    ) : IntentResult()

    data class NeedsClarification(
        val gaps: List<String>
    ) : IntentResult()

    data class Rejected(
        val reason: String,
        val details: List<String> = emptyList()
    ) : IntentResult()
}
