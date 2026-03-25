package com.agoii.mobile.core

import com.google.gson.annotations.SerializedName

/**
 * Immutable event record — the sole unit of state in the system.
 *
 * Fields:
 *  - [type]           Event type; MUST be a member of [EventTypes.ALL].
 *  - [payload]        Arbitrary key-value data describing the event.
 *  - [id]             UUID assigned at write time for idempotency.  Empty string on legacy events.
 *  - [sequenceNumber] Monotonic counter within the project ledger (0-based). -1 on legacy events.
 *  - [timestamp]      Wall-clock milliseconds (System.currentTimeMillis()) at write time. 0 on legacy.
 *
 * The two-argument form `Event(type, payload)` is preserved for in-memory test fixtures and
 * backward-compatible deserialization of existing ledger files.
 */
data class Event(
    @SerializedName("type")           val type:           String,
    @SerializedName("payload")        val payload:        Map<String, Any> = emptyMap(),
    @SerializedName("id")             val id:             String           = "",
    @SerializedName("sequenceNumber") val sequenceNumber: Long             = -1L,
    @SerializedName("timestamp")      val timestamp:      Long             = 0L
)
