package com.agoii.mobile.core

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException

class LedgerWriteException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

class EventStore(private val context: Context) {

    private val gson = Gson()

    private fun ledgerFile(projectId: String): File {
        val dir = File(context.filesDir, "ledgers")
        dir.mkdirs()
        return File(dir, "$projectId.json")
    }

    private fun tmpFile(projectId: String): File {
        val file = ledgerFile(projectId)
        return File(file.parent, "${file.name}.tmp")
    }

    internal fun appendEvent(projectId: String, event: Event, priorEvents: List<Event>) {
        val file = ledgerFile(projectId)
        val tmp = tmpFile(projectId)

        if (tmp.exists()) {
            throw LedgerWriteException("Concurrent write detected for '$projectId'")
        }

        val newEvents = priorEvents + event

        try {
            tmp.writeText(gson.toJson(newEvents))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw LedgerWriteException("Atomic rename failed for '$projectId'")
            }
        } catch (e: IOException) {
            tmp.delete()
            throw LedgerWriteException("Write failed for '$projectId': ${e.message}", e)
        }
    }

    fun loadEvents(projectId: String): List<Event> {
        val file = ledgerFile(projectId)
        val tmp = tmpFile(projectId)

        if (tmp.exists()) {
            tmp.delete()
        }

        if (!file.exists()) return emptyList()

        val events: List<Event> = try {
            val listType = object : TypeToken<List<Event>>() {}.type
            gson.fromJson<List<Event>>(file.readText(), listType) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            throw LedgerCorruptionException("Invalid JSON in '$projectId': ${e.message}", e)
        } catch (e: IOException) {
            throw LedgerCorruptionException("Cannot read '$projectId': ${e.message}", e)
        }

        return events
    }

    internal fun clearLedger(projectId: String) {
        ledgerFile(projectId).delete()
    }
}
