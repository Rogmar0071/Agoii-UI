package com.agoii.mobile.core

import com.google.gson.annotations.SerializedName

/**
 * Immutable event record — the sole unit of state in the system.
 * Structure must never change: { "type": String, "payload": Object }
 */
data class Event(
    @SerializedName("type") val type: String,
    @SerializedName("payload") val payload: Map<String, Any> = emptyMap()
)
