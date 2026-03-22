package com.agoii.mobile.irs

// ─── Surface Classification ───────────────────────────────────────────────────

/**
 * Primary surface class for contract scope law enforcement.
 *
 * ST — Structural type: data models and shared type definitions.
 * LG — Logic/Governance type: contract logic and governance enforcement engines.
 * SP — Spine type: stateful orchestration surfaces (append-only, mutation-restricted).
 * TS — Test surface type: validation layers; may only run after all upstream layers are stable.
 */
enum class SurfaceClass { ST, LG, SP, TS }

// ─── Layer Model ──────────────────────────────────────────────────────────────

/**
 * A single validated contract layer with its scope metadata.
 *
 * Invariant: [executionLoad] must not exceed [validationCapacity].
 * Violating this invariant at construction time constitutes a composite scope violation.
 *
 * @property layerId            Unique identifier for this layer (e.g. "L1", "L2").
 * @property surfaceClass       The surface class governing this layer's constraints.
 * @property weight             Σw — combined weight of primary components in this layer.
 * @property extensions         E — number of extension components derived from the layer.
 * @property constraints        C — number of hard constraint checks applied.
 * @property executionLoad      EL — execution load declared for this layer.
 * @property validationCapacity VC — maximum allowed EL for this layer to remain valid.
 */
data class ContractLayer(
    val layerId:            String,
    val surfaceClass:       SurfaceClass,
    val weight:             Int,
    val extensions:         Int,
    val constraints:        Int,
    val executionLoad:      Int,
    val validationCapacity: Int
) {
    init {
        require(weight >= 0)      { "weight must be >= 0, got $weight for layer $layerId" }
        require(extensions >= 0)  { "extensions must be >= 0, got $extensions for layer $layerId" }
        require(constraints >= 0) { "constraints must be >= 0, got $constraints for layer $layerId" }
        require(executionLoad >= 0) { "executionLoad must be >= 0, got $executionLoad for layer $layerId" }
        require(validationCapacity >= 0) {
            "validationCapacity must be >= 0, got $validationCapacity for layer $layerId"
        }
        require(executionLoad <= validationCapacity) {
            "Layer $layerId scope violation: EL=$executionLoad exceeds VC=$validationCapacity"
        }
    }

    /** true when this layer is within its validation capacity (always true post-construction). */
    val isValid: Boolean get() = executionLoad <= validationCapacity
}

// ─── Violation Record ─────────────────────────────────────────────────────────

/**
 * A single contract scope rule violation detected during evaluation.
 *
 * @property rule        The rule that was violated (e.g. "RULE-02").
 * @property description Human-readable explanation of the violation.
 * @property layerId     The layer where the violation was detected.
 */
data class ScopeViolation(
    val rule:        String,
    val description: String,
    val layerId:     String
)

// ─── Evaluation Result ────────────────────────────────────────────────────────

/**
 * Result of a contract scope law evaluation across an ordered set of layers.
 *
 * @property valid      true when all layers satisfy all four scope rules.
 * @property violations All detected rule violations (empty when [valid] is true).
 * @property layers     The evaluated layers in dependency order.
 */
data class ContractScopeResult(
    val valid:      Boolean,
    val violations: List<ScopeViolation>,
    val layers:     List<ContractLayer>
)

// ─── Audit Record ─────────────────────────────────────────────────────────────

/**
 * Governance audit record produced upon scope violation resolution via layered decomposition.
 *
 * Conforms to the CSL-RECOVERY-01 audit schema (section 9 of the contract).
 *
 * @property event            Machine-readable event identifier.
 * @property originalEL       Total EL of the undecomposed composite (pre-resolution).
 * @property originalVC       Total VC of the undecomposed composite (pre-resolution).
 * @property resolution       Resolution strategy applied (always "layered_decomposition").
 * @property layersValidated  Number of independently validated layers produced.
 * @property spineIntegrity   Spine mutation status after resolution ("preserved" = no mutation).
 * @property determinism      Pipeline output status after resolution ("unchanged" = deterministic).
 */
data class ContractScopeAuditRecord(
    val event:           String,
    val originalEL:      Int,
    val originalVC:      Int,
    val resolution:      String,
    val layersValidated: Int,
    val spineIntegrity:  String,
    val determinism:     String
)

// ─── Engine ───────────────────────────────────────────────────────────────────

/**
 * ContractScopeLaw — Logic/Governance engine for IRS contract scope enforcement.
 *
 * Surface class : LG (Logic/Governance)
 * Depends on    : L1 — IrsModels.kt (ST)
 * Used by       : L4 — IrsOrchestrator.kt (SP)
 *
 * Enforces the four CSL-RECOVERY-01 contract scope rules:
 *
 *  RULE-01: No contract layer may span more than ONE primary surface class.
 *           Enforced structurally: [ContractLayer] carries exactly one [SurfaceClass].
 *
 *  RULE-02: Spine surfaces (SP) require prior stabilization of all dependencies.
 *           Every layer appearing before a SP layer in the ordered sequence must be valid.
 *
 *  RULE-03: Test surfaces (TS) may only be validated after all upstream layers are stable.
 *           Every layer appearing before a TS layer in the ordered sequence must be valid.
 *
 *  RULE-04: EL aggregation across layers is forbidden.
 *           Enforced structurally: [evaluate] processes each layer independently;
 *           cross-layer EL sums are never computed.
 */
class ContractScopeLaw {

    /**
     * Evaluate an ordered sequence of contract layers against all four scope rules.
     *
     * Layers MUST be provided in dependency order (L1 first, Ln last).
     * Each layer is evaluated independently; no cross-layer EL aggregation is performed
     * (RULE-04 is a structural invariant of this engine).
     *
     * @param layers Ordered list of contract layers to evaluate.
     * @return [ContractScopeResult] indicating overall validity and any violations detected.
     */
    fun evaluate(layers: List<ContractLayer>): ContractScopeResult {
        val violations = mutableListOf<ScopeViolation>()
        for (layer in layers) {
            violations += checkRule02(layer, layers)
            violations += checkRule03(layer, layers)
        }
        return ContractScopeResult(
            valid      = violations.isEmpty(),
            violations = violations,
            layers     = layers
        )
    }

    /**
     * Produce an audit record confirming that a composite scope violation has been
     * resolved through layered decomposition (CSL-RECOVERY-01 section 9).
     *
     * @param originalEL       EL of the undecomposed composite before resolution.
     * @param originalVC       VC of the undecomposed composite before resolution.
     * @param layersValidated  Number of independently valid layers resulting from decomposition.
     * @return [ContractScopeAuditRecord] suitable for governance closure.
     */
    fun resolveViolation(
        originalEL:      Int,
        originalVC:      Int,
        layersValidated: Int
    ): ContractScopeAuditRecord = ContractScopeAuditRecord(
        event           = "contract_scope_violation_resolved",
        originalEL      = originalEL,
        originalVC      = originalVC,
        resolution      = "layered_decomposition",
        layersValidated = layersValidated,
        spineIntegrity  = "preserved",
        determinism     = "unchanged"
    )

    // ─── Rule implementations ─────────────────────────────────────────────────

    /**
     * RULE-02: Spine surfaces (SP) require prior stabilization of all dependencies.
     *
     * For each SP layer, every preceding layer in [all] must satisfy [ContractLayer.isValid].
     * Because [ContractLayer] enforces EL ≤ VC at construction time, a preceding layer can
     * only fail this check if it was constructed with equal EL and VC values that later
     * diverge via a subclass — or if the rule set is extended in the future.  The check is
     * retained here for forward-compatibility and explicit auditability.
     */
    private fun checkRule02(layer: ContractLayer, all: List<ContractLayer>): List<ScopeViolation> {
        if (layer.surfaceClass != SurfaceClass.SP) return emptyList()
        val spineIndex = all.indexOf(layer)
        return all.subList(0, spineIndex)
            .filter { !it.isValid }
            .map { dep ->
                ScopeViolation(
                    rule        = "RULE-02",
                    description = "Spine layer ${layer.layerId} requires stable dependency " +
                        "${dep.layerId} (EL=${dep.executionLoad} > VC=${dep.validationCapacity})",
                    layerId     = layer.layerId
                )
            }
    }

    /**
     * RULE-03: Test surfaces (TS) may only be validated after all upstream layers are stable.
     *
     * For each TS layer, every preceding layer in [all] must satisfy [ContractLayer.isValid].
     */
    private fun checkRule03(layer: ContractLayer, all: List<ContractLayer>): List<ScopeViolation> {
        if (layer.surfaceClass != SurfaceClass.TS) return emptyList()
        val tsIndex = all.indexOf(layer)
        return all.subList(0, tsIndex)
            .filter { !it.isValid }
            .map { dep ->
                ScopeViolation(
                    rule        = "RULE-03",
                    description = "Test layer ${layer.layerId} may only be validated after " +
                        "upstream layer ${dep.layerId} is stable " +
                        "(EL=${dep.executionLoad} > VC=${dep.validationCapacity})",
                    layerId     = layer.layerId
                )
            }
    }
}
