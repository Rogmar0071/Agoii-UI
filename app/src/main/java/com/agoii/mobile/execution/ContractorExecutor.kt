package com.agoii.mobile.execution

import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractors.*
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventTypes

// ─── ContractorExecutor ───────────────────────────────────────────────────────

/**
 * Input contract for a single contractor execution call (LEGACY).
 *
 * @property taskId              Unique task identifier.
 * @property taskDescription     What the task must accomplish.
 * @property taskPayload         Structured key-value data the contractor needs to execute.
 * @property contractConstraints Constraint labels that bound the execution.
 * @property expectedOutputSchema Human-readable description of the expected output structure.
 */
@Deprecated("Use FEL flow with TaskAssignedContract instead")
data class ContractorExecutionInput(
    val taskId:               String,
    val taskDescription:      String,
    val taskPayload:          Map<String, Any>,
    val contractConstraints:  List<String>,
    val expectedOutputSchema: String
)

/**
 * Output contract for a single contractor execution call (LEGACY).
 *
 * @property taskId          Unique task identifier (mirrors the input).
 * @property resultArtifact  The structured output produced by the contractor.
 * @property status          [ExecutionStatus.SUCCESS] or [ExecutionStatus.FAILURE].
 * @property error           Optional error message when [status] is FAILURE.
 */
@Deprecated("Use ContractorResult instead")
data class ContractorExecutionOutput(
    val taskId:         String,
    val resultArtifact: Map<String, Any>,
    val status:         ExecutionStatus,
    val error:          String? = null
)

/** Status of a single contractor execution. */
enum class ExecutionStatus { SUCCESS, FAILURE }

/**
 * ContractorExecutor — handles contractor invocation within the execution lifecycle.
 *
 * This component activates the First Execution Loop (FEL) by triggering contractor
 * invocation AFTER Governor emits TASK_ASSIGNED and BEFORE TASK_STARTED begins.
 *
 * Rules:
 *  - Invocation triggered by TASK_ASSIGNED event
 *  - Uses ContractorsModule for selection
 *  - Uses ContractorInvocationLayer for execution
 *  - Preserves contract_id and report_reference (RRID)
 *  - NO new orchestration layers
 *  - NO modification of Governor logic
 *
 * Flow:
 *   Governor → TASK_ASSIGNED event
 *   → ContractorExecutor.executeFromTaskAssigned()
 *   → ContractorsModule.resolve()
 *   → ContractorInvocationLayer.invoke()
 *   → ContractorResult (in-memory)
 *   → Governor continues → TASK_STARTED
 */
class ContractorExecutor(
    private val registry: ContractorRegistry = RealContractorRegistry(),
    private val matchingEngine: DeterministicMatchingEngine = DeterministicMatchingEngine(),
    private val invocationLayer: ContractorInvocationLayer = ContractorInvocationLayer()
) {

    /**
     * Execute contractor invocation triggered by TASK_ASSIGNED event.
     *
     * This is the FEL activation point — invocation happens within the execution
     * lifecycle, not externally.
     *
     * @param events Full event ledger (to extract contract details).
     * @param taskAssignedEvent The TASK_ASSIGNED event triggering execution.
     * @return ContractorResult with execution outcome.
     */
    fun executeFromTaskAssigned(
        events: List<Event>,
        taskAssignedEvent: Event
    ): ContractorResult {
        require(taskAssignedEvent.type == EventTypes.TASK_ASSIGNED) {
            "Expected TASK_ASSIGNED event, got: ${taskAssignedEvent.type}"
        }

        // Extract task details from TASK_ASSIGNED event
        val taskId = taskAssignedEvent.payload["taskId"] as? String
            ?: return errorResult("Missing taskId", "unknown", "unknown")

        val position = resolveInt(taskAssignedEvent.payload["position"])
            ?: return errorResult("Missing position", taskId, "unknown")

        // Extract contract_id and report_reference from CONTRACTS_GENERATED event
        val contractsGenerated = events.firstOrNull { 
            it.type == EventTypes.CONTRACTS_GENERATED 
        } ?: return errorResult("No CONTRACTS_GENERATED event found", taskId, "unknown")

        val reportId = contractsGenerated.payload["report_id"] as? String
            ?: return errorResult("Missing report_id", taskId, "unknown")

        val contracts = contractsGenerated.payload["contracts"] as? List<*>
            ?: return errorResult("Missing contracts list", taskId, reportId)

        val contractData = contracts.firstOrNull { contract ->
            (contract as? Map<*, *>)?.get("position")?.let { resolveInt(it) } == position
        } as? Map<*, *> ?: return errorResult("Contract not found for position $position", taskId, reportId)

        val contractId = contractData["contractId"] as? String
            ?: return errorResult("Missing contractId", taskId, reportId)

        // Build ExecutionContract for ContractorsModule
        val executionContract = ExecutionContract(
            contractId = contractId,
            reportReference = reportId,
            position = position.toString()
        )

        // Define contract requirements
        // TODO: These should be derived from contract payload in production
        val requirements = listOf(
            ContractRequirement("code_generation", 3, 1.0),
            ContractRequirement("reasoning", 2, 0.5)
        )

        // Select contractor via ContractorsModule
        val taskContract = matchingEngine.resolve(
            contract = executionContract,
            requirements = requirements,
            registry = registry
        )

        // Build contract payload
        val contractPayload = mapOf(
            "taskId" to taskId,
            "contractId" to contractId,
            "position" to position,
            "reportReference" to reportId,
            "objective" to "Execute contract $contractId at position $position"
        )

        // Invoke contractor via ContractorInvocationLayer
        return invocationLayer.invoke(taskContract, contractPayload)
    }

    /**
     * LEGACY: Execute a task using the given [contractor] (DEPRECATED).
     *
     * Use executeFromTaskAssigned() for FEL-compliant execution instead.
     */
    @Deprecated("Use executeFromTaskAssigned() for FEL flow")
    fun execute(
        input:      ContractorExecutionInput,
        contractor: ContractorProfile
    ): ContractorExecutionOutput {
        return try {
            val artifact = runExecutionLegacy(input, contractor)
            ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = artifact,
                status         = ExecutionStatus.SUCCESS
            )
        } catch (e: Exception) {
            ContractorExecutionOutput(
                taskId         = input.taskId,
                resultArtifact = emptyMap(),
                status         = ExecutionStatus.FAILURE,
                error          = e.message ?: "Unknown execution error"
            )
        }
    }

    private fun errorResult(
        message: String,
        contractId: String,
        reportRef: String
    ): ContractorResult {
        return ContractorResult(
            contract_id = contractId,
            report_reference = reportRef,
            contractor_id = "none",
            output = mapOf("error" to message),
            status = "failure",
            error = message
        )
    }

    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }

    private fun runExecutionLegacy(
        input:      ContractorExecutionInput,
        contractor: ContractorProfile
    ): Map<String, Any> {
        val cap = contractor.capabilities
        require(cap.capabilityScore > 0) {
            "Contractor '${contractor.id}' has zero capability score; cannot execute task."
        }
        return mapOf(
            "taskId"              to input.taskId,
            "contractorId"        to contractor.id,
            "capabilityScore"     to cap.capabilityScore,
            "constraintsMet"      to input.contractConstraints,
            "outputSchema"        to input.expectedOutputSchema,
            "executionPayload"    to input.taskPayload,
            "reliabilityRatio"    to contractor.reliabilityRatio
        )
    }
}
