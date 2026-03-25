package com.agoii.mobile.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException

/**
 * Thrown when a physical write to the ledger file fails.
 *
 * System Law 6 — Atomic Commit: either full write or nothing.
 */
class LedgerWriteException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Pure persistence layer for the Agoii event ledger.
 *
 * [EventStore] is exclusively responsible for reading and writing ledger files.
 * It contains ZERO sequencing logic, ZERO UUID generation, ZERO timestamp assignment,
 * and ZERO validation. All of that belongs to [EventLedger].
 *
 * Responsibilities:
 *  - [loadEvents]   — deserialise the project ledger file and verify structural integrity.
 *  - [appendEvent]  — accept a fully-constructed [Event] and the caller-supplied [priorEvents]
 *                     list from [EventLedger] and persist the combined list atomically via a
 *                     temp-file rename. Performs ZERO internal reads.
 *
 * System Law 6 — Atomic Commit: temp-file swap guarantees all-or-nothing writes.
 * System Law 7 — Fail-Fast Integrity: [loadEvents] raises [LedgerCorruptionException]
 *                on any structural violation.
 */
class EventStore(private val context: Context) {

    private val gson      = Gson()
    private val integrity = LedgerIntegrity()

    private fun ledgerFile(projectId: String): File {
        val dir = File(context.filesDir, "ledgers")
        dir.mkdirs()
        return File(dir, "$projectId.json")
    }

    /**
     * Persist a single pre-constructed [event] to [projectId]'s ledger.
     *
     * [priorEvents] is the ordered event list already held by [EventLedger] under the project
     * lock — this method appends [event] to that list and commits the result atomically.
     * Performs ZERO internal reads.
     *
     * @throws LedgerWriteException if a stale temp file is detected or the atomic commit fails.
     */
    internal fun appendEvent(projectId: String, event: Event, priorEvents: List<Event>) {
        persist(projectId, priorEvents + event)
    }

    /**
     * Load all events in insertion order and verify structural integrity.
     *
     * Returns an empty list when no ledger file exists yet.
     *
     * @throws LedgerCorruptionException if the file contains invalid JSON or fails
     *         the [LedgerIntegrity] monotonicity / uniqueness checks.
     */
    fun loadEvents(projectId: String): List<Event> {
        val file = ledgerFile(projectId)
        val tmp  = File(file.parent, "${file.name}.tmp")
        if (tmp.exists()) tmp.delete()
        if (!file.exists()) return emptyList()
        val events: List<Event> = try {
            val listType = object : TypeToken<List<Event>>() {}.type
            gson.fromJson<List<Event>>(file.readText(), listType) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            throw LedgerCorruptionException(
                "Ledger for '$projectId' contains invalid JSON: ${e.message}", e
            )
        } catch (e: IOException) {
            throw LedgerCorruptionException(
                "Cannot read ledger for '$projectId': ${e.message}", e
            )
        }
        integrity.verify(projectId, events)
        return events
    }

    private fun persist(projectId: String, events: List<Event>) {
        val file = ledgerFile(projectId)
        val tmp  = File(file.parent, "${file.name}.tmp")
        if (tmp.exists()) {
            throw LedgerWriteException(
                "Stale temp file detected for ledger '$projectId'. " +
                    "Concurrent write or prior crash detected — aborting to prevent data loss."
            )
        }
        try {
            tmp.writeText(gson.toJson(events))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw LedgerWriteException(
                    "Atomic rename failed for ledger '$projectId'. Temp file deleted."
                )
            }
        } catch (e: IOException) {
            tmp.delete()
            throw LedgerWriteException("Write failed for ledger '$projectId': ${e.message}", e)
        }
    }

    /**
     * Delete the entire ledger file for [projectId].
     *
     * For test use only. Production code must never call this method.
     */
    internal fun clearLedger(projectId: String) {
        ledgerFile(projectId).delete()
    }
}
