package com.agoii.mobile.intent

import com.agoii.mobile.irs.IntentData

/**
 * Certified intent artifact produced by [IntentModule] when IRS returns
 * [com.agoii.mobile.irs.OrchestratorResult.Certified].
 *
 * CONTRACT: INTENT_MODULE_V1_TIGHT
 *   - Immutable; constructed only inside [IntentModule]
 *   - NO IRS internal state exposure (no IrsSession, no timestamps)
 *   - NO ledger references, NO execution state
 *
 * @property sessionId   Unique IRS session identifier (opaque reference, not IRS state).
 * @property intentData  Certified intent fields (may be scout-enriched).
 */
data class IntentMaster(
    val sessionId:  String,
    val intentData: IntentData
)
