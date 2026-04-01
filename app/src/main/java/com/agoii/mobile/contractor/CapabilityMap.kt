package com.agoii.mobile.contractor

import com.agoii.mobile.contracts.ContractCapability

// ─── CapabilityMap ────────────────────────────────────────────────────────────

/**
 * Structured definition of a single contractor capability.
 *
 * @property name         Canonical dimension name (matches [ContractCapability.dimensionName]).
 * @property inputTypes   Accepted input artifact types for this capability.
 * @property outputTypes  Produced output artifact types for this capability.
 * @property guarantees   Behavioural guarantees provided when this capability is active.
 * @property limitations  Known limitations that callers must account for.
 */
data class CapabilityDefinition(
    val name:        String,
    val inputTypes:  List<String>,
    val outputTypes: List<String>,
    val guarantees:  List<String>,
    val limitations: List<String>
)

/**
 * CapabilityMap — registry-level catalogue of all recognised capability definitions.
 *
 * Rules:
 *  - Each entry is keyed by the canonical [ContractCapability.dimensionName].
 *  - Definitions are immutable and deterministic across all executions.
 *  - No external I/O; no runtime mutation.
 */
object CapabilityMap {

    val definitions: Map<String, CapabilityDefinition> = mapOf(

        ContractCapability.CONSTRAINT_OBEDIENCE.dimensionName to CapabilityDefinition(
            name        = ContractCapability.CONSTRAINT_OBEDIENCE.dimensionName,
            inputTypes  = listOf("constraint_set", "execution_context"),
            outputTypes = listOf("constrained_output"),
            guarantees  = listOf("all_constraints_respected", "no_boundary_violations"),
            limitations = listOf("depends_on_constraint_clarity")
        ),

        ContractCapability.STRUCTURAL_ACCURACY.dimensionName to CapabilityDefinition(
            name        = ContractCapability.STRUCTURAL_ACCURACY.dimensionName,
            inputTypes  = listOf("schema_definition", "output_template"),
            outputTypes = listOf("structured_output"),
            guarantees  = listOf("schema_conformance", "field_completeness"),
            limitations = listOf("requires_well_formed_schema")
        ),

        ContractCapability.COMPLEXITY_CAPACITY.dimensionName to CapabilityDefinition(
            name        = ContractCapability.COMPLEXITY_CAPACITY.dimensionName,
            inputTypes  = listOf("multi_step_task", "compound_objective"),
            outputTypes = listOf("decomposed_execution_result"),
            guarantees  = listOf("multi_step_handling", "context_retention"),
            limitations = listOf("performance_degrades_at_extreme_depth")
        ),

        ContractCapability.RELIABILITY.dimensionName to CapabilityDefinition(
            name        = ContractCapability.RELIABILITY.dimensionName,
            inputTypes  = listOf("deterministic_task"),
            outputTypes = listOf("consistent_output"),
            guarantees  = listOf("output_determinism", "reproducibility"),
            limitations = listOf("depends_on_input_stability")
        ),

        "external_execution" to CapabilityDefinition(
            name        = "external_execution",
            inputTypes  = listOf("TaskPayload"),
            outputTypes = listOf("ContractReport"),
            guarantees  = listOf("Sandboxed execution", "Policy enforcement"),
            limitations = listOf("No autonomy", "No persistence")
        ),

        "human_interaction" to CapabilityDefinition(
            name        = "human_interaction",
            inputTypes  = listOf("Intent"),
            outputTypes = listOf("HumanResponse"),
            guarantees  = listOf("Natural language interpretation"),
            limitations = listOf("Non-deterministic input")
        )
    )
}
