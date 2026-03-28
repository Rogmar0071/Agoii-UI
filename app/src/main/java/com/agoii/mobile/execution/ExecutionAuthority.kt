// AGOII CONTRACT — EXECUTION AUTHORITY MODULE (EXTENDED)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Phase 1 (evaluate):    Validate and authorize execution contracts BEFORE ledger write.
// Phase 2 (executeFromLedger): Own the full task execution pipeline from ledger state:
//   - trigger detection (TASK_STARTED)
//   - deterministic matching (DeterministicMatchingEngine)
//   - contractor execution (ContractorExecutor)
//   - contract report generation (AERP-1)
//   - validation against report (ResultValidator)
//   - TASK_EXECUTED ledger emission
//   - RCF-1 recovery contract issuance on failure

package com.agoii.mobile.execution

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractors.Capability
import com.agoii.mobile.contractors.ContractRequirement
import com.agoii.mobile.contractors.DeterministicMatchingEngine
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.ics.IcsExecutionResult
import com.agoii.mobile.ics.IcsModule
import com.agoii.mobile.tasks.Task
import com.agoii.mobile.tasks.TaskAssignmentStatus

// ---------- PHASE 1 INPUT / OUTPUT (unchanged) ----------

data class ExecutionContractInput(
    val contracts: List<ExecutionContract>,
    val reportId: String
)

data class ExecutionContract(
    val contractId: String,
    val name: String,
    val position: Int,
    val reportReference: String
)

sealed class ExecutionAuthorityResult {

    data class Approved(
        val orderedContracts: List<ExecutionContract>
    ) : ExecutionAuthorityResult()

    data class Blocked(
        val reason: String
    ) : ExecutionAuthorityResult()
}

// ---------- PHASE 2 MODELS ----------

/**
 * Deterministic, ledger-derived input for a single task execution.
 * All fields extracted exclusively from the ledger — no implicit reconstruction.
 */
data class ExecutionTask(
    val taskId:          String,
    val contractId:      String,
    val position:        Int,
    val total:           Int,
    val requirements:    List<Any>,
    val constraints:     List<String>,
    val expectedOutput:  String,
    val reportReference: String
)

/**
 * Structured contract report produced after execution (AERP-1).
 * Validation operates only against this report, not against raw execution output.
 *
 * Mandatory fields (AERP-1 hard enforcement):
 *  - reportReference  (RRID)
 *  - taskId
 *  - contractId
 *  - contractorId
 *  - typeInventory
 *  - executionSteps
 *  - artifactStructure
 */
data class ContractReport(
    val reportReference:   String,
    val taskId:            String,
    val contractId:        String,
    val contractorId:      String,
    val typeInventory:     List<String>,
    val executionSteps:    List<String>,
    val artifactStructure: Map<String, Any>,
    val errorConditions:   List<String>,
    val traceStructure:    Map<String, Any>
)

/**
 * Immutable anchor derived from [ContractReport] (AERP-1).
 *
 * Extracted once per execution attempt and embedded into every [ExecutionRecoveryContract].
 * MUST NOT be modified after extraction.
 *
 * @property reportReference    RRID that produced this execution.
 * @property validatedTypes     Type inventory at the moment of anchoring (= ContractReport.typeInventory).
 * @property validatedStructure Key-set of the artifact structure at the moment of anchoring.
 * @property validatedPaths     Execution steps recorded in the report.
 */
data class AnchorState(
    val reportReference:    String,
    val validatedTypes:     List<String>,
    val validatedStructure: Set<String>,
    val validatedPaths:     List<String>
)

/**
 * Recovery contract issued on execution or validation failure (RCF-1).
 * One contract = ONE violation surface. Anchor state is immutable.
 *
 * FAILURE_REFERENCE: contractId + taskId + executionPosition
 * ANCHOR_STATE:      Immutable snapshot derived from [ContractReport] (AERP-1).
 * VIOLATION_SURFACE: Single, atomic failing unit — no grouping permitted.
 */
data class ExecutionRecoveryContract(
    val contractId:          String,
    val taskId:              String,
    val contractType:        String,
    val executionPosition:   Int,
    val reportReference:     String,
    val failureClass:        String,
    val anchorState:         AnchorState,
    val violationSurface:    String,
    val correctionDirective: String,
    val constraintLock:      String,
    val successCondition:    String
)

/**
 * Result of [ExecutionAuthority.executeFromLedger].
 */
sealed class ExecutionAuthorityExecutionResult {
    /** TASK_EXECUTED was successfully written to the ledger. */
    data class Executed(
        val taskId:            String,
        val executionStatus:   ExecutionStatus,
        val validationVerdict: ValidationVerdict
    ) : ExecutionAuthorityExecutionResult()

    /**
     * Execution was blocked; TASK_EXECUTED(FAILURE) written and one [ExecutionRecoveryContract]
     * per violation surface issued (RCF-1, VIOLATION_SURFACE ISOLATION enforced).
     */
    data class BlockedWithRecovery(
        val reason:            String,
        val recoveryContracts: List<ExecutionRecoveryContract>
    ) : ExecutionAuthorityExecutionResult()

    /** TASK_EXECUTED already exists with SUCCESS for this taskId — idempotent guard triggered. */
    object Idempotent : ExecutionAuthorityExecutionResult()

    /** Last ledger event is not TASK_STARTED — trigger condition not met. */
    object NotTriggered : ExecutionAuthorityExecutionResult()

    /** Retry limit (MAX_RETRY) exceeded — CONTRACT_FAILED emitted, convergence halted. */
    data class RetryExceeded(val taskId: String) : ExecutionAuthorityExecutionResult()
}

// ---------- EXECUTION AUTHORITY ----------

/**
 * ExecutionAuthority — sole authority for contract validation, task execution, assembly, and ICS.
 *
 * Phase 1 — [evaluate]: validates and authorises execution contracts before ledger write.
 * Phase 2 — [executeFromLedger]: owns the full task execution pipeline from ledger state.
 * Phase 3 — [assembleFromLedger]: owns the full assembly pipeline after EXECUTION_COMPLETED.
 * Phase 4 — [runIcsFromLedger]: owns the ICS pipeline after ASSEMBLY_COMPLETED.
 *
 * @param contractorRegistry Optional contractor registry for deterministic matching.
 *                           When null, all execution attempts are BLOCKED (RCF-1 issued).
 */
class ExecutionAuthority(
    private val contractorRegistry: ContractorRegistry? = null
) {

    private val matchingEngine  = DeterministicMatchingEngine()
    private val executor        = ContractorExecutor()
    private val validator       = ResultValidator()
    private val assemblyModule  = AssemblyModule()
    private val icsModule       = IcsModule()

    companion object {
        /**
         * Maximum execution attempts per (taskId, reportReference) pair.
         * When this limit is reached, CONTRACT_FAILED is emitted and convergence halts.
         * NO infinite loops are permitted (CONVERGENCE LOOP CONTROL — RCF-1).
         */
        const val MAX_RETRY = 3
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 1 — Contract validation gate (pre-ledger write)
    // ═══════════════════════════════════════════════════════════════════════

    fun evaluate(input: ExecutionContractInput): ExecutionAuthorityResult {

        val reportId  = input.reportId
        val contracts = input.contracts

        // ---------- RULE 0: REPORT ID PRESENT ----------

        if (reportId.isBlank()) {
            return ExecutionAuthorityResult.Blocked("MISSING_REPORT_ID")
        }

        // ---------- GUARD: INCOMPLETE CONTRACT ----------

        if (contracts.isEmpty()) {
            return ExecutionAuthorityResult.Blocked("INCOMPLETE_CONTRACT")
        }

        // ---------- RULE 1: NON-EMPTY ----------

        if (contracts.isEmpty()) {
            return ExecutionAuthorityResult.Blocked("EMPTY_CONTRACTS")
        }

        // ---------- RULE 2: FIELD VALIDATION ----------

        for (contract in contracts) {

            if (contract.contractId.isBlank()) {
                return ExecutionAuthorityResult.Blocked("INVALID_FIELD")
            }

            if (contract.name.isBlank()) {
                return ExecutionAuthorityResult.Blocked("INVALID_FIELD")
            }

            if (contract.position <= 0) {
                return ExecutionAuthorityResult.Blocked("INVALID_FIELD")
            }

            if (contract.reportReference.isBlank()) {
                return ExecutionAuthorityResult.Blocked("MISSING_REPORT_REFERENCE")
            }

            if (contract.reportReference != reportId) {
                return ExecutionAuthorityResult.Blocked("REPORT_REFERENCE_MISMATCH")
            }
        }

        // ---------- RULE 3: POSITION SEQUENCE ----------

        val sorted = contracts.sortedBy { it.position }

        val expectedPositions = (1..contracts.size).toList()
        val actualPositions = sorted.map { it.position }

        if (expectedPositions != actualPositions) {
            return ExecutionAuthorityResult.Blocked("INVALID_POSITION_SEQUENCE")
        }

        // ---------- RULE 4: UNIQUE CONTRACT IDS ----------

        val ids = contracts.map { it.contractId }
        if (ids.size != ids.toSet().size) {
            return ExecutionAuthorityResult.Blocked("DUPLICATE_CONTRACT_ID")
        }

        // ---------- RULE 5: DETERMINISTIC ORDER ----------

        return ExecutionAuthorityResult.Approved(sorted)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 2 — Task execution pipeline (post-TASK_STARTED ledger state)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Execute the task pipeline triggered by a TASK_STARTED ledger event.
     *
     * Trigger condition: latest ledger event == TASK_STARTED AND no TASK_EXECUTED
     * exists yet for the same taskId (idempotency guard).
     *
     * Pipeline (locked):
     *  1. Extract [ExecutionTask] from ledger (BLOCK if any required field missing)
     *  2. Deterministic matching via [DeterministicMatchingEngine] (BLOCK + RCF-1 if no contractor)
     *  3. Construct [ContractorExecutionInput] deterministically
     *  4. Execute via [ContractorExecutor]
     *  5. Generate [ContractReport] (AERP-1)
     *  6. Validate via [ResultValidator] against report
     *  7. Emit TASK_EXECUTED to ledger
     *  8. On failure: issue [ExecutionRecoveryContract] (RCF-1)
     *
     * @param projectId Project ledger identifier.
     * @param ledger    EventLedger — single write authority.
     * @return [ExecutionAuthorityExecutionResult] describing the pipeline outcome.
     */
    fun executeFromLedger(
        projectId: String,
        ledger: EventLedger
    ): ExecutionAuthorityExecutionResult {

        val events = ledger.loadEvents(projectId)

        // ── Trigger check: last event must be TASK_STARTED ──────────────────
        val lastEvent = events.lastOrNull()
        if (lastEvent?.type != EventTypes.TASK_STARTED) {
            return ExecutionAuthorityExecutionResult.NotTriggered
        }

        val taskId = lastEvent.payload["taskId"] as? String
            ?: return ExecutionAuthorityExecutionResult.NotTriggered

        // ── Idempotency / retry gate ────────────────────────────────────────
        // A successful TASK_EXECUTED for this taskId means the work is done; block.
        // A failed TASK_EXECUTED counts as a prior attempt; allow retry up to MAX_RETRY.
        val priorSuccessful = events.any {
            it.type == EventTypes.TASK_EXECUTED &&
            it.payload["taskId"] == taskId &&
            it.payload["executionStatus"] == ExecutionStatus.SUCCESS.name
        }
        if (priorSuccessful) {
            return ExecutionAuthorityExecutionResult.Idempotent
        }
        val priorFailedAttempts = events.count {
            it.type == EventTypes.TASK_EXECUTED &&
            it.payload["taskId"] == taskId &&
            it.payload["executionStatus"] != ExecutionStatus.SUCCESS.name
        }
        if (priorFailedAttempts >= MAX_RETRY) {
            // CONVERGENCE LOOP CONTROL: retry ceiling reached — emit CONTRACT_FAILED and halt.
            val priorTaskAssigned = events.lastOrNull {
                it.type == EventTypes.TASK_ASSIGNED && it.payload["taskId"] == taskId
            }
            val contractId      = priorTaskAssigned?.payload?.get("contractId")?.toString() ?: "UNKNOWN"
            val reportReference = priorTaskAssigned?.payload?.get("report_reference")?.toString() ?: "UNKNOWN"
            try {
                ledger.appendEvent(
                    projectId,
                    EventTypes.CONTRACT_FAILED,
                    mapOf(
                        "taskId"           to taskId,
                        "contractId"       to contractId,
                        "report_reference" to reportReference,
                        "reason"           to "MAX_RETRY_EXCEEDED",
                        "retryCount"       to priorFailedAttempts
                    )
                )
            } catch (_: Exception) {
                // CONTRACT_FAILED ledger write failure is non-recoverable at this stage;
                // RetryExceeded is still returned to halt the convergence loop in-memory.
            }
            return ExecutionAuthorityExecutionResult.RetryExceeded(taskId)
        }

        // ── Step 1: Extract ExecutionTask from TASK_ASSIGNED event ───────────
        val taskAssignedEvent = events.lastOrNull {
            it.type == EventTypes.TASK_ASSIGNED && it.payload["taskId"] == taskId
        } ?: return blockWithRecovery(
            projectId, ledger, null, "NO_TASK_ASSIGNED",
            "No TASK_ASSIGNED event found for taskId=$taskId"
        )

        val executionTask = extractExecutionTask(taskId, taskAssignedEvent)
            ?: return blockWithRecovery(
                projectId, ledger, null, "MISSING_REQUIRED_FIELD",
                "ExecutionTask cannot be reconstructed: required field absent in TASK_ASSIGNED"
            )

        // ── Step 2: Registry check ───────────────────────────────────────────
        val registry = contractorRegistry
            ?: return blockWithRecovery(
                projectId, ledger, executionTask, "NO_CONTRACTOR_REGISTRY",
                "No ContractorRegistry available — matching is impossible"
            )

        // ── Step 2a: Deterministic matching ─────────────────────────────────
        val matchingContract = com.agoii.mobile.contractors.ExecutionContract(
            contractId      = executionTask.contractId,
            reportReference = executionTask.reportReference,
            position        = executionTask.position.toString()
        )
        val requirements = parseRequirements(executionTask.requirements)
        val adaptedRegistry = adaptRegistry(registry)
        val assigned = matchingEngine.resolve(matchingContract, requirements, adaptedRegistry)

        if (assigned.assignment.mode == com.agoii.mobile.contractors.AssignmentMode.BLOCKED) {
            return blockWithRecovery(
                projectId, ledger, executionTask, "MATCHING_BLOCKED",
                "DeterministicMatchingEngine: no valid contractor (BLOCKED)"
            )
        }

        val contractorId = assigned.assignment.contractorIds.firstOrNull()
            ?: return blockWithRecovery(
                projectId, ledger, executionTask, "MATCHING_NO_CONTRACTOR_ID",
                "Matching resolved but returned empty contractorIds"
            )

        val contractorProfile = registry.allVerified().find { it.id == contractorId }
            ?: return blockWithRecovery(
                projectId, ledger, executionTask, "CONTRACTOR_NOT_IN_REGISTRY",
                "Matched contractorId='$contractorId' not found in verified registry"
            )

        // ── Step 3: Construct ContractorExecutionInput deterministically ─────
        val executionInput = ContractorExecutionInput(
            taskId               = executionTask.taskId,
            taskDescription      = executionTask.expectedOutput,
            taskPayload          = mapOf(
                "contractId" to executionTask.contractId,
                "position"   to executionTask.position
            ),
            contractConstraints  = executionTask.constraints,
            expectedOutputSchema = executionTask.expectedOutput
        )

        // ── Step 4: Execute via ContractorExecutor ───────────────────────────
        val executionOutput = executor.execute(executionInput, contractorProfile)

        // ── Step 5: Generate ContractReport (AERP-1) ─────────────────────────
        val contractReport = generateContractReport(executionTask, executionOutput, assigned.trace, contractorId)

        // ── Step 6: Build report-backed Task and validate ────────────────────
        val task = Task(
            taskId               = executionTask.taskId,
            contractReference    = executionTask.contractId,
            stepReference        = executionTask.position,
            module               = "EXECUTION",
            description          = executionTask.expectedOutput,
            requiredCapabilities = emptyMap(),
            constraints          = executionTask.constraints,
            expectedOutput       = executionTask.expectedOutput,
            validationRules      = listOf(
                "artifact_must_be_non_empty",
                "taskId_must_match",
                "constraints_must_be_met",
                "execution_status_must_be_success"
            ),
            assignedContractorId = contractorId,
            assignmentStatus     = TaskAssignmentStatus.ASSIGNED
        )

        // Wrap artifact through report for AERP-1 compliance: validation is against report
        val reportBackedOutput = ContractorExecutionOutput(
            taskId         = executionOutput.taskId,
            resultArtifact = contractReport.artifactStructure,
            status         = executionOutput.status,
            error          = executionOutput.error
        )

        val validationResult = validator.validate(task, reportBackedOutput)

        // ── Step 7 / 8: Emit TASK_EXECUTED + RCF-1 on failure ───────────────
        val execStatusStr  = executionOutput.status.name
        val validStatusStr = validationResult.verdict.name
        val artifactRef    = buildArtifactReference(executionTask.reportReference, executionTask.taskId)

        ledger.appendEvent(
            projectId,
            EventTypes.TASK_EXECUTED,
            mapOf(
                "taskId"            to executionTask.taskId,
                "contractId"        to executionTask.contractId,
                "contractorId"      to contractorId,
                "artifactReference" to artifactRef,
                "executionStatus"   to execStatusStr,
                "validationStatus"  to validStatusStr,
                "validationReasons" to validationResult.failureReasons,
                "report_reference"  to executionTask.reportReference,
                "position"          to executionTask.position,
                "total"             to executionTask.total
            )
        )

        return if (executionOutput.status == ExecutionStatus.SUCCESS &&
            validationResult.verdict == ValidationVerdict.VALIDATED
        ) {
            ExecutionAuthorityExecutionResult.Executed(
                taskId            = executionTask.taskId,
                executionStatus   = ExecutionStatus.SUCCESS,
                validationVerdict = ValidationVerdict.VALIDATED
            )
        } else {
            // ── AERP-1 Anchor extraction ─────────────────────────────────────
            val anchorState = extractAnchorState(contractReport)

            // ── VIOLATION SURFACE ISOLATION (RCF-1) ──────────────────────────
            // One ViolationSurface = one RecoveryContract. Multiple failures produce
            // multiple sequential RecoveryContracts — never grouped.
            val violations: List<String> = when {
                executionOutput.status == ExecutionStatus.FAILURE ->
                    listOf("EXECUTION_FAILED: ${executionOutput.error ?: "unknown"}")
                else ->
                    validationResult.failureReasons.map { "VALIDATION_FAILED: $it" }
            }
            val failureClass = when {
                executionOutput.status == ExecutionStatus.FAILURE -> "STRUCTURAL"
                else -> "LOGICAL"
            }

            val recoveries = violations.map { violationSurface ->
                val recovery = issueRecoveryContract(
                    task            = executionTask,
                    anchorState     = anchorState,
                    failureClass    = failureClass,
                    violationSurface = violationSurface
                )
                writeRecoveryContractToLedger(projectId, ledger, recovery, artifactRef)
                recovery
            }

            ExecutionAuthorityExecutionResult.BlockedWithRecovery(
                reason            = violations.first(),
                recoveryContracts = recoveries
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 3 — Assembly pipeline (post-EXECUTION_COMPLETED ledger state)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Execute the assembly pipeline triggered by EXECUTION_COMPLETED.
     *
     * Delegates exclusively to [AssemblyModule], which:
     *  - enforces all trigger conditions (all contracts complete, no duplicate, CONTRACTS_GENERATED present)
     *  - reconstructs [com.agoii.mobile.assembly.AssemblyInput] from ledger only (RRIL-1)
     *  - appends ASSEMBLY_STARTED, ASSEMBLY_VALIDATED, ASSEMBLY_COMPLETED to [ledger]
     *  - returns the structured [com.agoii.mobile.assembly.FinalArtifact]
     *
     * GOVERNANCE RULE: Governor MUST NOT call this method.
     * Only ExecutionAuthority is permitted to invoke assembly (via [CoreBridge]).
     *
     * @param projectId Project ledger identifier.
     * @param ledger    EventLedger — single write authority.
     * @return [AssemblyExecutionResult] describing the pipeline outcome.
     */
    fun assembleFromLedger(
        projectId: String,
        ledger:    EventLedger
    ): AssemblyExecutionResult = assemblyModule.assemble(projectId, ledger)

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 4 — ICS pipeline (post-ASSEMBLY_COMPLETED ledger state)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Execute the ICS processing pipeline triggered by ASSEMBLY_COMPLETED.
     *
     * Delegates exclusively to [IcsModule], which:
     *  - enforces all trigger conditions (last event == ASSEMBLY_COMPLETED, required payload fields,
     *    no duplicate ICS_COMPLETED for same report_reference)
     *  - reconstructs [com.agoii.mobile.ics.IcsInput] from ledger only (RRIL-1)
     *  - appends ICS_STARTED and ICS_COMPLETED to [ledger]
     *  - returns the structured [com.agoii.mobile.ics.IcsOutput]
     *
     * GOVERNANCE RULE: Governor MUST NOT call this method.
     * Only ExecutionAuthority is permitted to invoke ICS (via [com.agoii.mobile.bridge.CoreBridge]).
     *
     * @param projectId Project ledger identifier.
     * @param ledger    EventLedger — single write authority.
     * @return [IcsExecutionResult] describing the pipeline outcome.
     */
    fun runIcsFromLedger(
        projectId: String,
        ledger:    EventLedger
    ): IcsExecutionResult = icsModule.process(projectId, ledger)

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extract [ExecutionTask] from a TASK_ASSIGNED event.
     * Returns null if any required field is absent (triggers BLOCK).
     */
    private fun extractExecutionTask(
        taskId:           String,
        taskAssignedEvent: com.agoii.mobile.core.Event
    ): ExecutionTask? {
        val payload = taskAssignedEvent.payload

        val contractId      = payload["contractId"]       as? String ?: return null
        val position        = resolveInt(payload["position"])         ?: return null
        val total           = resolveInt(payload["total"])            ?: return null
        val reportReference = payload["report_reference"] as? String
            ?: return null  // RRIL-1: BLOCK if RRID absent

        if (reportReference.isBlank()) return null  // RRIL-1: BLOCK if RRID blank

        @Suppress("UNCHECKED_CAST")
        val requirements = payload["requirements"] as? List<Any>
            ?: return null  // BLOCK if requirements key absent

        @Suppress("UNCHECKED_CAST")
        val constraints = (payload["constraints"] as? List<*>)
            ?.filterIsInstance<String>()
            ?: emptyList()

        return ExecutionTask(
            taskId          = taskId,
            contractId      = contractId,
            position        = position,
            total           = total,
            requirements    = requirements,
            constraints     = constraints,
            expectedOutput  = "$contractId-output",
            reportReference = reportReference
        )
    }

    /**
     * Build a BLOCKED result: emits TASK_EXECUTED(FAILURE) and issues RCF-1.
     * Used for all hard-block conditions before ContractReport is available.
     * AnchorState is minimal (no validated artifact fields exist yet).
     */
    private fun blockWithRecovery(
        projectId:     String,
        ledger:        EventLedger,
        task:          ExecutionTask?,
        failureClass:  String,
        reason:        String
    ): ExecutionAuthorityExecutionResult.BlockedWithRecovery {

        // VIOLATION 4: artifactReference must always be deterministic and referenceable — never "NONE"
        val artifactRef = if (task != null)
            buildArtifactReference(task.reportReference, task.taskId, "NO_ARTIFACT")
        else
            buildArtifactReference("UNKNOWN", "UNKNOWN", "NO_ARTIFACT")

        // Emit TASK_EXECUTED(FAILURE) when we have enough context
        if (task != null) {
            try {
                ledger.appendEvent(
                    projectId,
                    EventTypes.TASK_EXECUTED,
                    mapOf(
                        "taskId"            to task.taskId,
                        "contractId"        to task.contractId,
                        "contractorId"      to "NONE",
                        "artifactReference" to artifactRef,
                        "executionStatus"   to "FAILURE",
                        "validationStatus"  to "FAILED",
                        "validationReasons" to listOf(reason),
                        "report_reference"  to task.reportReference,
                        "position"          to task.position,
                        "total"             to task.total
                    )
                )
            } catch (_: Exception) {
                // Ledger write failure does not suppress the block result
            }
        }

        // Minimal AnchorState — no ContractReport available at this stage
        val anchorState = AnchorState(
            reportReference    = task?.reportReference ?: "UNKNOWN",
            validatedTypes     = emptyList(),
            validatedStructure = emptySet(),
            validatedPaths     = emptyList()
        )

        val recovery = issueRecoveryContract(task, anchorState, failureClass, reason)
        // VIOLATION 3: RecoveryContract MUST be ledger-materialized (RCF-1)
        writeRecoveryContractToLedger(projectId, ledger, recovery, artifactRef)
        return ExecutionAuthorityExecutionResult.BlockedWithRecovery(reason, listOf(recovery))
    }

    /** Generate [ContractReport] from execution output (AERP-1 compliance). */
    private fun generateContractReport(
        task:         ExecutionTask,
        output:       ContractorExecutionOutput,
        trace:        com.agoii.mobile.contractors.ResolutionTrace,
        contractorId: String
    ): ContractReport {
        val artifact = output.resultArtifact
        return ContractReport(
            reportReference   = task.reportReference,
            taskId            = task.taskId,
            contractId        = task.contractId,
            contractorId      = contractorId,
            typeInventory     = artifact.keys.toList(),
            executionSteps    = listOf("MATCHING_RESOLVED", "EXECUTION_INVOKED", "ARTIFACT_PRODUCED"),
            artifactStructure = artifact,
            errorConditions   = listOfNotNull(output.error),
            traceStructure    = mapOf(
                "taskId"          to task.taskId,
                "contractId"      to task.contractId,
                "evaluated"       to trace.evaluated,
                "matched"         to trace.matched,
                "executionStatus" to output.status.name
            )
        )
    }

    /**
     * Extract an immutable [AnchorState] from a [ContractReport] (AERP-1).
     *
     * The AnchorState is captured at the moment validation begins and MUST NOT be modified
     * thereafter. It is embedded in every [ExecutionRecoveryContract] issued for this attempt.
     */
    private fun extractAnchorState(report: ContractReport): AnchorState = AnchorState(
        reportReference    = report.reportReference,
        validatedTypes     = report.typeInventory.toList(),
        validatedStructure = report.artifactStructure.keys.toSet(),
        validatedPaths     = report.executionSteps.toList()
    )

    /** Issue [ExecutionRecoveryContract] (RCF-1) for a SINGLE violation surface. */
    private fun issueRecoveryContract(
        task:            ExecutionTask?,
        anchorState:     AnchorState,
        failureClass:    String,
        violationSurface: String
    ): ExecutionRecoveryContract = ExecutionRecoveryContract(
        contractId          = task?.contractId ?: "UNKNOWN",
        taskId              = task?.taskId     ?: "UNKNOWN",
        contractType        = "TASK_EXECUTION",
        executionPosition   = task?.position   ?: -1,
        reportReference     = anchorState.reportReference,
        failureClass        = failureClass,
        anchorState         = anchorState,
        violationSurface    = violationSurface,
        correctionDirective = "Resolve $failureClass for task '${task?.taskId ?: "UNKNOWN"}' " +
                              "at position ${task?.position ?: -1}: $violationSurface",
        constraintLock      = "ANCHOR_STATE is IMMUTABLE — no modification to validated fields permitted",
        successCondition    = "TASK_EXECUTED written with executionStatus=SUCCESS AND validationStatus=VALIDATED"
    )

    /**
     * Write [ExecutionRecoveryContract] to the ledger as a RECOVERY_CONTRACT event (RCF-1).
     * All recovery MUST be ledger-materialized; in-memory recovery is PROHIBITED.
     * Includes FAILURE_REFERENCE fields (taskId, report_reference) for ledger traceability.
     */
    private fun writeRecoveryContractToLedger(
        projectId:  String,
        ledger:     EventLedger,
        recovery:   ExecutionRecoveryContract,
        artifactRef: String
    ) {
        try {
            ledger.appendEvent(
                projectId,
                EventTypes.RECOVERY_CONTRACT,
                mapOf(
                    "contractId"          to recovery.contractId,
                    "taskId"              to recovery.taskId,
                    "contractType"        to recovery.contractType,
                    "executionPosition"   to recovery.executionPosition,
                    "report_reference"    to recovery.reportReference,
                    "failureClass"        to recovery.failureClass,
                    "violationSurface"    to recovery.violationSurface,
                    "correctionDirective" to recovery.correctionDirective,
                    "successCondition"    to recovery.successCondition,
                    "artifactReference"   to artifactRef
                )
            )
        } catch (_: Exception) {
            // Ledger write failure is recorded but does not suppress the block result
        }
    }

    /** Parse raw requirements list from TASK_ASSIGNED payload into [ContractRequirement] list. */
    private fun parseRequirements(rawRequirements: List<Any>): List<ContractRequirement> =
        rawRequirements.filterIsInstance<Map<*, *>>().mapNotNull { map ->
            val capability    = map["capability"]    as? String ?: return@mapNotNull null
            val requiredLevel = resolveInt(map["requiredLevel"]) ?: return@mapNotNull null
            val weight        = (map["weight"] as? Number)?.toDouble() ?: 1.0
            if (requiredLevel in 0..5)
                ContractRequirement(capability, requiredLevel, weight)
            else null
        }

    /**
     * Adapt a [ContractorRegistry] (contractor package) to the
     * [com.agoii.mobile.contractors.ContractorRegistry] interface required by
     * [DeterministicMatchingEngine].
     */
    private fun adaptRegistry(
        source: ContractorRegistry
    ): com.agoii.mobile.contractors.ContractorRegistry =
        object : com.agoii.mobile.contractors.ContractorRegistry {
            override fun getAll(): List<com.agoii.mobile.contractors.ContractorProfile> =
                source.allVerified().map { p ->
                    com.agoii.mobile.contractors.ContractorProfile(
                        contractorId      = p.id,
                        capabilities      = listOf(
                            Capability("constraintObedience", p.capabilities.constraintObedience),
                            Capability("structuralAccuracy",  p.capabilities.structuralAccuracy),
                            Capability("complexityCapacity",  p.capabilities.complexityCapacity),
                            Capability("reliability",         p.capabilities.reliability)
                        ),
                        reliabilityScore  = p.reliabilityRatio,
                        costScore         = 0.0,
                        availabilityScore = 1.0
                    )
                }
        }

    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }

    /**
     * Builds a deterministic, referenceable artifactReference string.
     *
     * Format: `report:<reportReference>:<taskId>[:<suffix>]`
     *
     * The suffix is omitted for normal execution artifacts; `NO_ARTIFACT` is used
     * when execution was blocked before an artifact could be produced.
     */
    private fun buildArtifactReference(
        reportReference: String,
        taskId:          String,
        suffix:          String? = null
    ): String = buildString {
        append("report:")
        append(reportReference)
        append(":")
        append(taskId)
        if (suffix != null) {
            append(":")
            append(suffix)
        }
    }
}
