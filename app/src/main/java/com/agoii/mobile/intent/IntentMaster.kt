package com.agoii.mobile.intent

import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.IrsSession

/**
 * Certified intent artifact produced by [IntentModule] when IRS returns
 * [com.agoii.mobile.irs.OrchestratorResult.Certified].
 *
 * CONTRACT: INTENT_MODULE_V1
 *   - Immutable; constructed only inside [IntentModule]
 *   - Contains no ledger references, no execution state
 *   - [session] is retained for full traceability (replay, audit)
 *
 * @property sessionId    Unique IRS session identifier.
 * @property intentData   Certified intent fields (may be scout-enriched).
 * @property session      Full IRS session snapshot at the point of certification.
 * @property certifiedAt  Wall-clock milliseconds when certification was confirmed.
 */
data class IntentMaster(
    val sessionId:   String,
    val intentData:  IntentData,
    val session:     IrsSession,
    val certifiedAt: Long = System.currentTimeMillis()
)
