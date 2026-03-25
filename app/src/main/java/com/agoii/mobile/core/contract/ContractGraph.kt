package com.agoii.mobile.core.contract

import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.core.enforcement.SurfaceMap

// ─── Contract Graph ───────────────────────────────────────────────────────────

/**
 * Structural graph of a contract ready for enforcement validation.
 *
 * Constructed by [ContractModule] from a [ReplayStructuralState] and passed
 * through the [EnforcementPipeline] before any execution routing occurs.
 *
 * The graph is backed by a complete [SurfaceMap] that maps the real system
 * surface — source files, data classes, field usage, and dependencies — rather
 * than a flat list of field names.  This removes the graph-only abstraction and
 * binds enforcement to the actual structural surface of the system.
 *
 * @property contractId    Unique identifier of this contract.
 * @property state         Full structural state at the time of graph construction.
 * @property surface       Complete structural surface describing files, data classes,
 *                         field usage, and dependencies for this contract.
 * @property derivedFields Map of field name → derivation expression.
 *                         Only derivations permitted by Derivation Law are valid.
 */
data class ContractGraph(
    val contractId:    String,
    val state:         ReplayStructuralState,
    val surface:       SurfaceMap,
    val derivedFields: Map<String, String>
)
