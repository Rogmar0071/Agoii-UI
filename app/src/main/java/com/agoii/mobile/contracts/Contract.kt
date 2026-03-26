package com.agoii.mobile.contracts

// ─── Core Contract Model ──────────────────────────────────────────────────────

/**
 * Immutable, typed contract — the canonical unit of work in the Contract System.
 *
 * Every action that the system takes must be backed by a [Contract].  No module
 * may construct a [Contract] directly; all creation is delegated to
 * [ContractFactory] via the appropriate typed builder.
 *
 * @property id                 Unique contract identifier.
 * @property type               Classification from [ContractType].
 * @property objective          Human-readable statement of what this contract achieves.
 * @property scope              Ordered list of system boundaries this contract affects.
 * @property constraints        Conditions that execution must respect.
 * @property expectedOutput     Description of the result produced on successful completion.
 * @property completionCriteria Ordered, verifiable criteria that mark this contract complete.
 * @property metadata           Optional key/value annotations (traceability, tagging, etc.).
 */
data class Contract(
    val id:                 String,
    val type:               ContractType,
    val objective:          String,
    val scope:              List<String>,
    val constraints:        List<String>,
    val expectedOutput:     String,
    val completionCriteria: List<String>,
    val metadata:           Map<String, String> = emptyMap()
) {
    init {
        require(id.isNotBlank())             { "Contract id must not be blank" }
        require(objective.isNotBlank())      { "Contract objective must not be blank" }
        require(scope.isNotEmpty())          { "Contract scope must contain at least one entry" }
        require(expectedOutput.isNotBlank()) { "Contract expectedOutput must not be blank" }
        require(completionCriteria.isNotEmpty()) {
            "Contract completionCriteria must contain at least one criterion"
        }
    }
}
