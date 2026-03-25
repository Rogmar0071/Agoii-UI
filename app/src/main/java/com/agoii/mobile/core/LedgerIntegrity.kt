package com.agoii.mobile.core

/**
 * Thrown when a ledger file is found to be structurally corrupt and cannot be trusted.
 *
 * System Law 7 — Fail-Fast Integrity: corruption stops the system.
 */
class LedgerCorruptionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Post-load integrity verifier for a project's event list.
 *
 * Checks enforced:
 *  1. Events that carry a [Event.sequenceNumber] ≥ 0 must be strictly monotonically
 *     increasing (each sequenced event's number must be greater than the previous one's).
 *  2. Events that carry a non-blank [Event.id] must all be unique (no duplicate IDs).
 *
 * Legacy events written before the ledger authority upgrade have [Event.sequenceNumber] = -1
 * and [Event.id] = "".  These are silently skipped so that existing ledger files remain
 * readable after the upgrade.
 *
 * System Law 3 — Deterministic Ordering (monotonic sequence).
 * System Law 7 — Fail-Fast Integrity (corruption stops system).
 */
class LedgerIntegrity {

    /**
     * Verify [events] for the given [projectId].
     *
     * @throws LedgerCorruptionException on any integrity violation.
     */
    fun verify(projectId: String, events: List<Event>) {
        verifyMonotonicity(projectId, events)
        verifyUniqueIds(projectId, events)
    }

    private fun verifyMonotonicity(projectId: String, events: List<Event>) {
        var prevSeq = Long.MIN_VALUE
        var prevWasSequenced = false
        for ((index, event) in events.withIndex()) {
            if (event.sequenceNumber < 0L) continue
            if (prevWasSequenced && event.sequenceNumber <= prevSeq) {
                throw LedgerCorruptionException(
                    "Non-monotonic sequence in ledger '$projectId': " +
                        "event[$index].sequenceNumber=${event.sequenceNumber} is not greater than previous=$prevSeq"
                )
            }
            prevSeq = event.sequenceNumber
            prevWasSequenced = true
        }
    }

    private fun verifyUniqueIds(projectId: String, events: List<Event>) {
        val seen = HashSet<String>()
        for ((index, event) in events.withIndex()) {
            val id = event.id
            if (id.isBlank()) continue
            if (!seen.add(id)) {
                throw LedgerCorruptionException(
                    "Duplicate event id '$id' detected at index $index in ledger '$projectId'"
                )
            }
        }
    }
}
