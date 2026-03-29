package com.agoii.mobile.ui

/**
 * Minimal bridge interface required by [handleSend].
 *
 * Decouples the send-routing logic from the full [com.agoii.mobile.bridge.CoreBridge]
 * constructor (which requires an Android Context), allowing the function to be
 * tested on the JVM without any Android dependencies.
 *
 * CONTRACT ID: AGOII-CT-UI-EMISSION-GUARD-01
 */
interface UiSendBridge {

    /**
     * Submit a new intent with the given objective text.
     * Called when no prior events exist for the project (first submission).
     */
    fun submitIntent(projectId: String, objective: String): Boolean

    /**
     * Update an existing intent with a revised objective.
     * Called during the intent-evolution phase (INTENT_SUBMITTED or INTENT_UPDATED last).
     */
    fun updateIntent(projectId: String, objective: String): Boolean
}
