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
 *  - [appendEvent]  — accept a fully-constructed [Event] from [EventLedger] and persist
 *                     it atomically via a temp-file rename. Does NOT call [loadEvents].
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
     * The [event] is appended to the raw list already on disk and the result is committed
     * atomically via a temp-file rename. This method does NOT call [loadEvents] —
     * the caller ([EventLedger]) has already read the current list under the project lock.
     *
     * @throws LedgerWriteException if the atomic commit cannot be completed.
     */
    fun appendEvent(projectId: String, event: Event) {
        val existing = readRaw(projectId)
        persist(projectId, existing + event)
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

    /**
     * Read the raw event list from disk without running an integrity check.
     * Used only inside [appendEvent] to build the write payload.
     */
    private fun readRaw(projectId: String): List<Event> {
        val file = ledgerFile(projectId)
        if (!file.exists()) return emptyList()
        return try {
            val listType = object : TypeToken<List<Event>>() {}.type
            gson.fromJson<List<Event>>(file.readText(), listType) ?: emptyList()
        } catch (e: IOException) {
            throw LedgerWriteException(
                "Cannot read ledger for '$projectId' during write: ${e.message}", e
            )
        }
    }

    /**
     * Atomically write [events] to [projectId]'s ledger file via a temp-file rename.
     *
     * @throws LedgerWriteException if the write or rename fails.
     */
    private fun persist(projectId: String, events: List<Event>) {
        val file = ledgerFile(projectId)
        val tmp  = File(file.parent, "${file.name}.tmp")
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
