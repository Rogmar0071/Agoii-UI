package com.agoii.mobile.core.contract

import com.agoii.mobile.core.ReplayStructuralState

// ─── Contract Graph ───────────────────────────────────────────────────────────

/**
 * Structural graph of a contract ready for enforcement validation.
 *
 * Constructed by [ContractModule] from a [ReplayStructuralState] and passed
 * through the [EnforcementPipeline] before any execution routing occurs.
 *
 * @property contractId     Unique identifier of this contract.
 * @property state          Full structural state at the time of graph construction.
 * @property declaredFields Ordered list of field paths referenced by this contract.
 *                          All paths must be sub-paths of [ReplayStructuralState].
 * @property derivedFields  Map of field name → derivation expression.
 *                          Only the derivation permitted by Derivation Law is valid.
 */
data class ContractGraph(
    val contractId:     String,
    val state:          ReplayStructuralState,
    val declaredFields: List<String>,
    val derivedFields:  Map<String, String>
)
