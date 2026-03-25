package com.agoii.mobile.core

class LedgerCorruptionException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class LedgerIntegrity {

    fun verify(projectId: String, events: List<Event>) {
        verifySequence(projectId, events)
        verifyTimestamps(projectId, events)
        verifyUniqueIds(projectId, events)
    }

    private fun verifySequence(projectId: String, events: List<Event>) {
        var expected = 0L

        for ((index, event) in events.withIndex()) {
            if (event.sequenceNumber != expected) {
                throw LedgerCorruptionException(
                    "Sequence violation in '$projectId' at index $index: expected=$expected actual=${event.sequenceNumber}"
                )
            }
            expected++
        }
    }

    private fun verifyTimestamps(projectId: String, events: List<Event>) {
        var prev = Long.MIN_VALUE

        for ((index, event) in events.withIndex()) {
            if (event.timestamp < prev) {
                throw LedgerCorruptionException(
                    "Timestamp violation in '$projectId' at index $index"
                )
            }
            prev = event.timestamp
        }
    }

    private fun verifyUniqueIds(projectId: String, events: List<Event>) {
        val seen = HashSet<String>()

        for ((index, event) in events.withIndex()) {
            val id = event.id
            if (id.isBlank() || !seen.add(id)) {
                throw LedgerCorruptionException(
                    "Duplicate or invalid id '$id' at index $index in '$projectId'"
                )
            }
        }
    }
}
