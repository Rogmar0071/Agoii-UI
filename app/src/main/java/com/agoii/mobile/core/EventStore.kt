package com.agoii.mobile.core

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException

/**
 * Append-only event store backed by per-project JSON files on the local filesystem.
 *
 * Invariants:
 *  - Events are NEVER deleted or modified.
 *  - Each write rewrites the complete list atomically via a temp-file swap.
 *  - loadEvents always returns events in insertion order.
 */
class EventStore(private val context: Context) : EventRepository {

    private val gson = Gson()

    private fun ledgerFile(projectId: String): File {
        val dir = File(context.filesDir, "ledgers")
        dir.mkdirs()
        return File(dir, "$projectId.json")
    }

    /** Append a single event to the ledger. This is the ONLY write path. */
    override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
        val events = loadEvents(projectId).toMutableList()
        events.add(Event(type, payload))
        val file = ledgerFile(projectId)
        val tmp = File(file.parent, "${file.name}.tmp")
        tmp.writeText(gson.toJson(events))
        tmp.renameTo(file)
    }

    /** Load all events in insertion order. Returns empty list if ledger does not exist. */
    override fun loadEvents(projectId: String): List<Event> {
        val file = ledgerFile(projectId)
        if (!file.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<Event>>() {}.type
            gson.fromJson<List<Event>>(file.readText(), type) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            Log.e(TAG, "Ledger for '$projectId' is corrupted: ${e.message}")
            emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read ledger for '$projectId': ${e.message}")
            emptyList()
        }
    }

    /** Remove the ledger for a project. Used in tests only. */
    fun clearLedger(projectId: String) {
        ledgerFile(projectId).delete()
    }

    companion object {
        private const val TAG = "EventStore"
    }
}
