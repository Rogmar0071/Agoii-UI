package com.agoii.mobile.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Thrown when a physical write to the ledger file fails.
 *
 * System Law 6 — Atomic Commit: either full write or nothing.
 */
class LedgerWriteException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Append-only event store backed by per-project JSON files on the local filesystem.
 *
 * Invariants:
 *  - Events are NEVER deleted or modified after being written.
 *  - Every appended event receives a UUID [Event.id], a monotonically increasing
 *    [Event.sequenceNumber] (= current list size at write time), and a wall-clock
 *    [Event.timestamp].
 *  - Each write is committed atomically via a temp-file swap (write → rename).
 *    If the rename fails the temp file is deleted and [LedgerWriteException] is thrown.
 *  - [loadEvents] verifies structural integrity via [LedgerIntegrity] and throws
 *    [LedgerCorruptionException] on any violation (System Law 7).
 *  - [loadEvents] always returns events in insertion order.
 *
 * Concurrency note: [EventStore] itself is not locked.  Callers that require
 * write-serialisation (System Law 5) must hold a [LedgerLock] across the
 * read → validate → write sequence.  [EventLedger] does this.
 */
class EventStore(private val context: Context) : EventRepository {

    private val gson      = Gson()
    private val integrity = LedgerIntegrity()

    private fun ledgerFile(projectId: String): File {
        val dir = File(context.filesDir, "ledgers")
        dir.mkdirs()
        return File(dir, "$projectId.json")
    }

    /**
     * Append a single event to the ledger.
     *
     * Assigns [Event.id] (UUID), [Event.sequenceNumber] (next position), and
     * [Event.timestamp] (current wall-clock ms) automatically.
     *
     * The write is atomic: the new list is serialised to a `.tmp` file first,
     * then renamed over the live ledger file.  On rename failure the temp file
     * is deleted and [LedgerWriteException] is thrown.
     *
     * @throws LedgerWriteException if the atomic commit cannot be completed.
     */
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        val existing  = loadEvents(projectId)
        val nextSeq   = existing.size.toLong()
        val newEvent  = Event(
            type           = type,
            payload        = payload,
            id             = UUID.randomUUID().toString(),
            sequenceNumber = nextSeq,
            timestamp      = System.currentTimeMillis()
        )
        val newList = existing + newEvent
        val file    = ledgerFile(projectId)
        val tmp     = File(file.parent, "${file.name}.tmp")
        try {
            tmp.writeText(gson.toJson(newList))
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
     * Load all events in insertion order.
     *
     * Returns an empty list when no ledger file exists yet.
     *
     * @throws LedgerCorruptionException if the file contains invalid JSON or fails
     *         the [LedgerIntegrity] monotonicity / uniqueness checks.
     */
    override fun loadEvents(projectId: String): List<Event> {
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
     * Delete the entire ledger file for [projectId].
     *
     * For test use only.  Production code must never call this method.
     */
    internal fun clearLedger(projectId: String) {
        ledgerFile(projectId).delete()
    }
}
