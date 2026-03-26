package com.agoii.mobile.contracts

// ─── Simulation Contract Builder ──────────────────────────────────────────────

/**
 * SimulationContractBuilder — constructs [ContractDefinition] instances of type
 * [ContractType.SIMULATION].
 *
 * A SIMULATION contract governs a non-authoritative dry-run execution.  It may
 * mirror the structure of an EXECUTION contract but carries an explicit constraint
 * that no ledger mutation occurs and no authoritative output is produced.
 *
 * Usage:
 * ```
 * val definition = SimulationContractBuilder()
 *     .objective("Simulate execution of project onboarding flow")
 *     .scope("IRS", "SIMULATION")
 *     .constraint("No ledger events may be emitted")
 *     .constraint("Output is advisory only — not authoritative")
 *     .expectedOutput("Simulation report with predicted step outcomes")
 *     .completionCriterion("All steps evaluated without ledger write")
 *     .completionCriterion("Simulation result returned to caller")
 *     .build()
 * ```
 *
 * Rules:
 *  - Always produces [ContractType.SIMULATION].
 *  - The no-ledger-mutation constraint is automatically appended; callers must
 *    not remove it.
 *  - Scope is pre-seeded with "SIMULATION".
 *  - The resulting [ContractDefinition] must be submitted to [ContractFactory.build]
 *    for id assignment and final validation.
 */
class SimulationContractBuilder {

    private var objective:          String                     = ""
    private val scope:              MutableList<String>        = mutableListOf("SIMULATION")
    private val constraints:        MutableList<String>        = mutableListOf(
        "No ledger events may be emitted during simulation",
        "Simulation output is advisory only — not authoritative"
    )
    private var expectedOutput:     String                     = ""
    private val completionCriteria: MutableList<String>        = mutableListOf()
    private val metadata:           MutableMap<String, String> = mutableMapOf()

    /** Sets the objective of this simulation contract. */
    fun objective(objective: String): SimulationContractBuilder =
        apply { this.objective = objective }

    /** Appends one or more scope entries. */
    fun scope(vararg entries: String): SimulationContractBuilder =
        apply { scope += entries }

    /** Appends a caller-defined constraint (in addition to the mandatory ones). */
    fun constraint(constraint: String): SimulationContractBuilder =
        apply { constraints += constraint }

    /** Sets the expected output description. */
    fun expectedOutput(expectedOutput: String): SimulationContractBuilder =
        apply { this.expectedOutput = expectedOutput }

    /** Appends a single completion criterion. */
    fun completionCriterion(criterion: String): SimulationContractBuilder =
        apply { completionCriteria += criterion }

    /** Attaches an optional metadata key/value pair. */
    fun metadata(key: String, value: String): SimulationContractBuilder =
        apply { metadata[key] = value }

    /**
     * Produces the [ContractDefinition] for submission to [ContractFactory].
     *
     * No validation is performed here; [ContractValidator] runs inside
     * [ContractFactory.build].
     */
    fun build(): ContractDefinition = ContractDefinition(
        type               = ContractType.SIMULATION,
        objective          = objective,
        scope              = scope.toList(),
        constraints        = constraints.toList(),
        expectedOutput     = expectedOutput,
        completionCriteria = completionCriteria.toList(),
        metadata           = metadata.toMap()
    )
}
