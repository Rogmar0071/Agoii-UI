package com.agoii.mobile.core

class LedgerValidationException(message: String) : RuntimeException(message)

class ValidationLayer {

    fun validate(
        projectId: String,
        type: String,
        payload: Map<String, Any>,
        currentEvents: List<Event>
    ) {

        if (type !in EventTypes.ALL) {
            throw LedgerValidationException("Unknown event type '$type' for '$projectId'")
        }

        if (currentEvents.isEmpty()) {
            if (type != EventTypes.INTENT_SUBMITTED) {
                throw LedgerValidationException(
                    "First event must be '${EventTypes.INTENT_SUBMITTED}'"
                )
            }
        } else {
            val lastType = currentEvents.last().type
            if (!LedgerAudit.isLegalTransition(lastType, type)) {
                throw LedgerValidationException(
                    "Illegal transition '$lastType' → '$type'"
                )
            }
        }

        if (payload.isEmpty()) {
            throw LedgerValidationException("Payload cannot be empty for '$type'")
        }

        for (key in payload.keys) {
            if (key.isBlank()) {
                throw LedgerValidationException("Payload contains blank key")
            }
        }
    }
}
