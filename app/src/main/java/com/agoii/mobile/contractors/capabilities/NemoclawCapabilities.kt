package com.agoii.mobile.contractors.capabilities

// ─── NemoClaw Capability Definitions ─────────────────────────────────────────
//
// CONTRACT: REGISTER_NEMOCLAW_CONTRACTOR — Section 2 (CAPABILITY DEFINITION)
// Defines the complete capability surface of the NemoClaw contractor.
// These definitions are authoritative for matching and validation.

/**
 * A single high-level capability definition for the NemoClaw contractor.
 *
 * @property name        Unique capability identifier.
 * @property inputTypes  Accepted input formats.
 * @property outputTypes Produced output types.
 * @property guarantees  Execution guarantees provided by this capability.
 * @property limitations Known constraints and prohibitions.
 */
data class NemoclawCapabilityDefinition(
    val name:        String,
    val inputTypes:  List<String>,
    val outputTypes: List<String>,
    val guarantees:  List<String>,
    val limitations: List<String>
)

/**
 * NemoclawCapabilities — authoritative capability manifest for the NemoClaw contractor.
 *
 * Capabilities: code_execution, agent_execution, sandboxed_tasks,
 *               llm_task_execution, external_tool_execution.
 *
 * All capabilities share the same I/O contract and guarantee/limitation surface.
 *
 * RULES:
 *  - No capability may be inferred; all are explicitly declared here.
 *  - These definitions are consumed by [com.agoii.mobile.contractors.matching.NemoclawMatcher]
 *    and the [com.agoii.mobile.contractor.ContractorRegistry] integration.
 */
object NemoclawCapabilities {

    // ── Capability identifiers ────────────────────────────────────────────────

    const val CODE_EXECUTION         = "code_execution"
    const val AGENT_EXECUTION        = "agent_execution"
    const val SANDBOXED_TASKS        = "sandboxed_tasks"
    const val LLM_TASK_EXECUTION     = "llm_task_execution"
    const val EXTERNAL_TOOL_EXECUTION = "external_tool_execution"

    val CAPABILITY_NAMES: List<String> = listOf(
        CODE_EXECUTION,
        AGENT_EXECUTION,
        SANDBOXED_TASKS,
        LLM_TASK_EXECUTION,
        EXTERNAL_TOOL_EXECUTION
    )

    // ── Shared I/O contract ───────────────────────────────────────────────────

    val INPUT_TYPES: List<String> = listOf(
        "structured_prompt",
        "execution_policy"
    )

    val OUTPUT_TYPES: List<String> = listOf(
        "ContractReport"
    )

    // ── Guarantees and limitations ────────────────────────────────────────────

    val GUARANTEES: List<String> = listOf(
        "deterministic_output_structure",
        "sandbox_isolation",
        "full_output_visibility"
    )

    val LIMITATIONS: List<String> = listOf(
        "no autonomous execution",
        "no internal decision making",
        "no retry capability",
        "no fallback logic"
    )

    // ── Full capability definitions ───────────────────────────────────────────

    val DEFINITIONS: List<NemoclawCapabilityDefinition> = CAPABILITY_NAMES.map { name ->
        NemoclawCapabilityDefinition(
            name        = name,
            inputTypes  = INPUT_TYPES,
            outputTypes = OUTPUT_TYPES,
            guarantees  = GUARANTEES,
            limitations = LIMITATIONS
        )
    }
}
