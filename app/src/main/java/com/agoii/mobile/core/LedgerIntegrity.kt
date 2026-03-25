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
 *  1. Sequence numbers must be strictly contiguous starting at 0:
 *     event[0].sequenceNumber == 0, event[n].sequenceNumber == n.
 *     No gaps, no repeats, no legacy -1 events permitted.
 *  2. Timestamps must be monotonically non-decreasing: each event's
 *     timestamp must be ≥ the previous event's timestamp.
 *  3. Every event id must be unique — no duplicate IDs.
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
        verifySequenceContinuity(projectId, events)
        verifyUniqueIds(projectId, events)
    }

    private fun verifySequenceContinuity(projectId: String, events: List<Event>) {
        var prevSeq      = -1L
        var prevTimestamp = Long.MIN_VALUE
        for ((index, event) in events.withIndex()) {
            val expectedSeq = prevSeq + 1L
            if (event.sequenceNumber != expectedSeq) {
                throw LedgerCorruptionException(
                    "Sequence violation in ledger '$projectId': " +
                        "event[$index].sequenceNumber=${event.sequenceNumber}, expected=$expectedSeq"
                )
            }
            if (event.timestamp < prevTimestamp) {
                throw LedgerCorruptionException(
                    "Timestamp regression in ledger '$projectId': " +
                        "event[$index].timestamp=${event.timestamp} is before previous=$prevTimestamp"
                )
            }
            prevSeq       = event.sequenceNumber
            prevTimestamp = event.timestamp
        }
    }

    private fun verifyUniqueIds(projectId: String, events: List<Event>) {
        val seen = HashSet<String>()
        for ((index, event) in events.withIndex()) {
            if (!seen.add(event.id)) {
                throw LedgerCorruptionException(
                    "Duplicate event id '${event.id}' detected at index $index in ledger '$projectId'"
                )
            }
        }
    }
}
