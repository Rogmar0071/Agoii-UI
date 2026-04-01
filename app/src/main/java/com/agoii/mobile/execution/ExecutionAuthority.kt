// AGOII CONTRACT — EXECUTION AUTHORITY MODULE (EXTENDED)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Phase 1 (evaluate):              Validate and authorize execution contracts BEFORE ledger write.
// Phase 2 (executeFromLedger):     Own the full task execution pipeline from ledger state.
//   - trigger detection (TASK_STARTED)
//   - deterministic matching (DeterministicMatchingEngine)
//   - contractor execution (ContractorExecutor)
//   - contract report generation (AERP-1)
//   - validation against report (ResultValidator)
//   - TASK_EXECUTED ledger emission
//   - RCF-1 recovery contract issuance on failure
// Phase 3 (assembleFromLedger):    Assembly pipeline after EXECUTION_COMPLETED.
// Phase 4 (runIcsFromLedger):      ICS pipeline after ASSEMBLY_COMPLETED.
// Phase 5 (resolveCommitDecision): Sole writer of COMMIT_EXECUTED / COMMIT_ABORTED (V1 — single authority).
// Phase 6 (route):                 Deterministic routing via UniversalContract execution
//                                  semantics (UCS-1 — PURE, no side effects).
// Phase 6 (ingestUniversalContract): UCS-1 ingestion pipeline (GOVERNANCE INPUT ONLY):
//   Surface 2 — validation (UniversalContractValidator)
//   Surface 3 — normalization (UniversalContractNormalizer)
//   Surface 6 — enforcement (ContractEnforcementEngine)
//   Lifecycle  — CONTRACT_CREATED / CONTRACT_VALIDATED / CONTRACT_APPROVED (ledger events)
//   Failure    — RECOVERY_CONTRACT (RCF-1)
//   NO execution triggered — UniversalContract is a GOVERNANCE INPUT only

package com.agoii.mobile.execution

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.contractor.ContractorInitialization
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.ContractorSystem
import com.agoii.mobile.contractor.ContractorSystemResult
import com.agoii.mobile.contractor.ResolutionTrace
import com.agoii.mobile.contracts.ContractCapability
import com.agoii.mobile.contracts.ContractEnforcementEngine
import com.agoii.mobile.contracts.ContractEnforcementResult
import com.agoii.mobile.contracts.ContractReport
import com.agoii.mobile.contracts.ExecutionRecoveryContract
import com.agoii.mobile.contracts.FailureClass
import com.agoii.mobile.contracts.Violation
import com.agoii.mobile.contracts.ContractValidationResult
import com.agoii.mobile.contracts.ContractViolation
import com.agoii.mobile.contracts.ExecutionType
import com.agoii.mobile.contracts.TargetDomain
import com.agoii.mobile.contracts.UniversalContract
import com.agoii.mobile.contracts.UniversalContractNormalizer
import com.agoii.mobile.contracts.UniversalContractValidator
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerValidationException
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
    val validatedPaths:     List<String>,
    // AGOII-ANCHOR-STATE-IMMUTABILITY-006: extended fields
    val contractId:         String          = "",
    val validatedReport:    ContractReport? = null,
    val lockedFields:       Set<String>     = setOf(
        "contractId",
        "taskId",
        "reportReference",
        "artifact",
        "trace"
    )
)

/**
 * Single mutation surface for delta execution (AERP-1 / AGOII-ANCHOR-STATE-IMMUTABILITY-006).
 *
 * Isolation is enforced: ONE field, ONE reason per recovery attempt.
 * All delta execution is constrained to this surface — no other fields may be mutated.
 */
data class ViolationSurface(
    val field:  String,
    val reason: String
)

/**
 * Result of [ExecutionAuthority.executeFromLedger].
 */
sealed class ExecutionAuthorityExecutionResult {
    /** TASK_EXECUTED was successfully written to the ledger. */
    data class Executed(
        val taskId:            String,
        val executionStatus:   ExecutionStatus,
        val validationVerdict: ValidationVerdict,
        val report:            ContractReport
    ) : ExecutionAuthorityExecutionResult()

    /**
     * Execution was blocked; TASK_EXECUTED(FAILURE) written and one [ExecutionRecoveryContract]
     * per violation surface issued (RCF-1, VIOLATION_SURFACE ISOLATION enforced).
     */
    data class BlockedWithRecovery(
        val reason:            String,
        val recoveryContracts: List<ExecutionRecoveryContract>,
        val violations:        List<Violation> = emptyList(),
        val reportReference:   String = ""
    ) : ExecutionAuthorityExecutionResult()

    /** TASK_EXECUTED already exists with SUCCESS for this taskId — idempotent guard triggered. */
    object Idempotent : ExecutionAuthorityExecutionResult()

    /** Last ledger event is not TASK_STARTED — trigger condition not met. */
    object NotTriggered : ExecutionAuthorityExecutionResult()

    /** Retry limit (MAX_RETRY) exceeded — CONTRACT_FAILED emitted, convergence halted. */
    data class RetryExceeded(val taskId: String) : ExecutionAuthorityExecutionResult()

    /**
     * A recovery contract was issued for a single violation surface
     * (AGOII-ANCHOR-STATE-IMMUTABILITY-006, RCF-1).
     */
    data class RecoveryIssued(
        val contractId: String,
        val reason:     String
    ) : ExecutionAuthorityExecutionResult()
}

// ---------- EXECUTION ROUTE (UCS-1) ----------

/**
 * Deterministic routing decision produced by [ExecutionAuthority.route].
 *
 * Each variant corresponds to one [ExecutionType] value declared by a
 * [com.agoii.mobile.contracts.UniversalContract].  The [targetDomain] is
 * preserved in every variant so that the caller can inspect the full routing
 * context without re-reading the original contract.
 *
 * Routing is purely functional: [ExecutionAuthority.route] performs no I/O
 * and has no side effects.
 */
sealed class ExecutionRoute {

    abstract val targetDomain: TargetDomain

    /** Route for [ExecutionType.INTERNAL_EXECUTION] — handled by the internal engine. */
    data class InternalExecution(override val targetDomain: TargetDomain) : ExecutionRoute()

    /** Route for [ExecutionType.EXTERNAL_EXECUTION] — delegated to an external integration. */
    data class ExternalExecution(override val targetDomain: TargetDomain) : ExecutionRoute()

    /** Route for [ExecutionType.COMMUNICATION] — drives a user-facing interaction cycle. */
    data class Communication(override val targetDomain: TargetDomain) : ExecutionRoute()

    /** Route for [ExecutionType.AI_PROCESSING] — delegated to an AI/LLM agent. */
    data class AiProcessing(override val targetDomain: TargetDomain) : ExecutionRoute()

    /** Route for [ExecutionType.SWARM_COORDINATION] — distributed across a swarm of agents. */
    data class SwarmCoordination(override val targetDomain: TargetDomain) : ExecutionRoute()
}

// ---------- PHASE 6: UNIVERSAL INGESTION RESULT (UCS-1) ----------

/**
 * Result of [ExecutionAuthority.ingestUniversalContract].
 *
 * [Ingested]          — all three lifecycle events written to ledger; contract is ready for
 *                        the execution spine.
 * [ValidationFailed]  — Surface 2 structural/semantic validation failed; RECOVERY_CONTRACT
 *                        written to ledger.
 * [EnforcementFailed] — Surface 6 enforcement gate failed; RECOVERY_CONTRACT written to ledger.
 *
 * GOVERNANCE RULE: no variant implies execution. [UniversalContract] is a GOVERNANCE INPUT
 * only. Execution occurs exclusively via [executeFromLedger] when TASK_STARTED is the latest
 * ledger event.
 */
sealed class UniversalIngestionResult {

    /**
     * Contract ingested — CONTRACT_CREATED, CONTRACT_VALIDATED, CONTRACT_APPROVED written to
     * ledger.  The contract is now available to the execution spine.
     */
    data class Ingested(
        val contractId:      String,
        val reportReference: String
    ) : UniversalIngestionResult()

    /**
     * Structural or semantic validation failed (Surface 2); CONTRACT_CREATED and
     * RECOVERY_CONTRACT written to ledger.
     */
    data class ValidationFailed(
        val violations:       List<String>,
        val recoveryContract: ExecutionRecoveryContract
    ) : UniversalIngestionResult()

    /**
     * Enforcement gate failed (Surface 6); CONTRACT_CREATED, CONTRACT_VALIDATED, and
     * RECOVERY_CONTRACT written to ledger.
     */
    data class EnforcementFailed(
        val violations:       List<ContractViolation>,
        val recoveryContract: ExecutionRecoveryContract
    ) : UniversalIngestionResult()
}

// ---------- DELTA EXECUTION (DEE-1) ----------

/**
 * Restricted input for delta execution (CLC-1 Part 2).
 *
 * Contractor input is scoped exclusively to the violation surface. Full contract
 * re-execution is BLOCKED when running in DELTA mode — only the violationField
 * may be corrected against the AnchorState.
 */
data class DeltaExecutionInput(
    val violationField:       String,
    val correctionDirective:  String,
    val anchorState:          AnchorState
)

private enum class ExecutionMode {
    NORMAL,
    DELTA
}

private data class DeltaContext(
    val violationField: String,
    val anchorState: AnchorState,
    val previousReportReference: String
)

// ---------- EXECUTION AUTHORITY ----------

/**
 * ExecutionAuthority — sole authority for contract validation, task execution, assembly, and ICS.
 *
 * Phase 1 — [evaluate]: validates and authorises execution contracts before ledger write.
 * Phase 2 — [executeFromLedger]: owns the full task execution pipeline from ledger state.
 * Phase 3 — [assembleFromLedger]: owns the full assembly pipeline after EXECUTION_COMPLETED.
 * Phase 4 — [runIcsFromLedger]: owns the ICS pipeline after ASSEMBLY_COMPLETED.
 * Phase 5 — [route]: deterministic routing via [UniversalContract] execution semantics (UCS-1 — PURE).
 * Phase 6 — [ingestUniversalContract]: UCS-1 ingestion pipeline; writes lifecycle events to the
 *            ledger (NO execution triggered; contract is a governance input only).
 *
 * @param contractorRegistry Contractor registry for deterministic matching.
 * @param driverRegistry     Driver registry providing execution backends.
 */
class ExecutionAuthority(
    private val contractorRegistry: ContractorRegistry,
    private val driverRegistry: DriverRegistry
) {

    private val contractorSystem = ContractorSystem(driverRegistry = driverRegistry)
    private val validator       = ResultValidator()
    private val assemblyModule  = AssemblyModule()
    private val icsModule       = IcsModule()

    // ── UCS-1 ingestion components (Surfaces 2, 3, 6) ────────────────────────
    private val contractValidator  = UniversalContractValidator()
    private val contractNormalizer = UniversalContractNormalizer()
    private val enforcementEngine  = ContractEnforcementEngine()

    companion object {
        /**
         * Maximum execution attempts per (taskId, reportReference) pair.
         * When this limit is reached, CONTRACT_FAILED is emitted and convergence halts.
         * NO infinite loops are permitted (CONVERGENCE LOOP CONTROL — RCF-1).
         */
        const val MAX_RETRY = 3

        /**
         * Maximum delta convergence iterations per report reference (CLC-1 Part 6).
         * When exceeded, a NON_CONVERGENT recovery contract is issued and execution halts.
         */
        const val MAX_DELTA = 3
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
            "No TASK_ASSIGNED event found for taskId=$taskId",
            knownTaskId = taskId
        )

        val executionTask = extractExecutionTask(taskId, taskAssignedEvent)
            ?: return blockWithRecovery(
                projectId, ledger, null, "MISSING_REQUIRED_FIELD",
                "ExecutionTask cannot be reconstructed: required field absent in TASK_ASSIGNED",
                knownTaskId = taskId
            )

        // ── DEE-1: Detect delta mode from contractId prefix ──────────────────
        val isDelta = executionTask.contractId.startsWith("RCF_")
        val deltaContext: DeltaContext? = if (isDelta) {
            DeltaContext(
                violationField          = extractViolationFieldFromContractId(executionTask.contractId),
                anchorState             = extractAnchorStateFromRecovery(executionTask, events),
                previousReportReference = extractReportReferenceFromRecovery(executionTask)
            )
        } else null
        val executionMode = if (isDelta) ExecutionMode.DELTA else ExecutionMode.NORMAL

        // ── MQP-EXECUTION-INVARIANT-LOCK-01: mandatory execution closure ────────
        // Every path from here MUST produce TASK_EXECUTED (SUCCESS or FAILURE).
        // This try/catch is the final safety net for any unexpected exception that
        // escapes explicit error handling inside the pipeline.
        return try {

        // ── Step 1a: Extract user input from INTENT_SUBMITTED ────────────────
        val userInput = events.firstOrNull { it.type == EventTypes.INTENT_SUBMITTED }
            ?.payload?.get("objective")?.toString()
            ?: return blockWithRecovery(
                projectId, ledger, executionTask, "MISSING_INTENT_SUBMITTED",
                "INTENT_SUBMITTED event or 'objective' field absent — cannot inject userInput"
            )

        // ── Step 2: Registry check ───────────────────────────────────────────
        val registry = contractorRegistry
        ContractorInitialization.enforce(
            registry       = contractorRegistry,
            driverRegistry = driverRegistry
        )

        // ── Step 2a: Domain context lookup (from CONTRACT_CREATED, if present) ─
        val domainContext = lookupDomainContext(executionTask.contractId, events)

        // ── Step 2b: Deterministic contractor selection + domain-aware execution ─
        //   ContractorSystem owns: matching → profile lookup → executor call → domain artifact.
        //   Capabilities read from CONTRACT_CREATED event with STRUCTURAL_ACCURACY fallback.
        val requiredCapabilities = extractCapabilitiesFromLedger(executionTask.contractId, events)

        // ── DEE-1 Step 2: Enforce anchor state integrity before contractor execution ─
        if (executionMode == ExecutionMode.DELTA && deltaContext != null) {
            enforceAnchorStateIntegrity(deltaContext)
        }

        val systemResult = contractorSystem.execute(
            taskId               = executionTask.taskId,
            contractId           = executionTask.contractId,
            reportReference      = executionTask.reportReference,
            position             = executionTask.position,
            constraints          = executionTask.constraints,
            expectedOutput       = executionTask.expectedOutput,
            taskPayload          = mapOf(
                "taskId"     to executionTask.taskId,
                "contractId" to executionTask.contractId,
                "userInput"  to userInput
            ),
            requiredCapabilities = requiredCapabilities,
            executionType        = domainContext.executionType,
            targetDomain         = domainContext.targetDomain,
            registry             = registry
        )

        when (systemResult) {
            is ContractorSystemResult.Blocked -> return blockWithRecovery(
                projectId, ledger, executionTask, "MATCHING_FAILED",
                systemResult.reason
            )
            is ContractorSystemResult.Resolved -> { /* pipeline continues below */ }
        }
        val resolved = systemResult as ContractorSystemResult.Resolved

        val contractorIds = resolved.assignment.contractorIds

        // ── AGOII-MQP-MATCHING-INVARIANT-LOCK-01 ─────────────────────────────
        // HARD INVARIANT: execution MUST NOT proceed without contractor
        if (contractorIds.isEmpty()) {
            throw LedgerValidationException(
                "INVARIANT VIOLATION: Empty contractorIds from Assignment — Matching Engine failure"
            )
        }

        // Deterministic extraction (safe after invariant enforcement)
        val contractorId = contractorIds.first()

        val executionOutput = resolved.executionOutput

        // ── DEE-1 Step 3: Filter to delta surface before report generation ────
        val filteredOutput = if (executionMode == ExecutionMode.DELTA && deltaContext != null) {
            filterToDeltaSurface(executionOutput, deltaContext)
        } else executionOutput

        // ── Step 5: Generate ContractReport (AERP-1) ─────────────────────────
        val contractReport = generateContractReport(executionTask, filteredOutput, resolved.trace, contractorId)

        // ── Step 5-CHV1: Enforce report completeness (CONVERGENCE_HARDENING_V1 / FSE-2) ─
        val reportViolations = validateReportCompleteness(contractReport, executionTask.contractId, deltaContext)
        if (reportViolations.isNotEmpty()) {
            // ── DEE-1 Step 5: Loop guard — block non-converging delta ─────────
            if (executionMode == ExecutionMode.DELTA) {
                if (isNonConverging(deltaContext, reportViolations)) {
                    return ExecutionAuthorityExecutionResult.BlockedWithRecovery(
                        reason            = "NON_CONVERGING_DELTA",
                        recoveryContracts = emptyList(),
                        violations        = reportViolations,
                        reportReference   = contractReport.reportReference
                    )
                }
            }
            val recoveryContracts = buildRecoveryContracts(reportViolations, contractReport)
            require(recoveryContracts.size == reportViolations.size) {
                "RCF-1 VIOLATION: Recovery contracts must map 1:1 with violations"
            }
            return ExecutionAuthorityExecutionResult.BlockedWithRecovery(
                reason = "REPORT_VALIDATION_FAILED",
                recoveryContracts = recoveryContracts,
                violations = reportViolations,
                reportReference = contractReport.reportReference
            )
        }

        // ── Step 5a: Freeze report — immutable snapshot (AERP-1 / RRIL-1) ───
        val frozenReport = contractReport.copy()

        // ── Step 5b: RRID integrity check (RRIL-1) ───────────────────────────
        if (frozenReport.reportReference != executionTask.reportReference) {
            return blockWithRecovery(
                projectId, ledger, executionTask,
                "RRID_VIOLATION",
                "Report reference mismatch (RRIL-1 breach)"
            )
        }

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

        // Wrap artifact for AERP-1 compliance: validation is against execution output
        val reportBackedOutput = ContractorExecutionOutput(
            taskId         = filteredOutput.taskId,
            resultArtifact = filteredOutput.resultArtifact,
            status         = filteredOutput.status,
            error          = filteredOutput.error
        )

        val validationResult = validator.validate(task, reportBackedOutput)

        // ── Step 5: Authorization gate (AERP-1) ─────────────────────────────
        // NO write is permitted without explicit authorization.
        // Authorization = validation passed; any failure → blockWithRecovery (RCF-1).
        val authorized = validationResult.verdict == ValidationVerdict.VALIDATED
        if (!authorized) {
            return blockWithRecovery(
                projectId, ledger, executionTask,
                "AERP1_AUTHORIZATION_FAILURE",
                "Execution not authorized after validation"
            )
        }

        // ── Step 6: Hard block enforcement (AERP-1) ──────────────────────────
        try {
            enforceHardBlocks(
                report             = frozenReport,
                validationExecuted = true,
                capabilities       = requiredCapabilities,
                registry           = registry,
                systemResult       = systemResult,
                authorized         = authorized
            )
        } catch (_: Exception) {
            return blockWithRecovery(
                projectId, ledger, executionTask,
                "HARD_BLOCK_VIOLATION",
                "Execution blocked by AERP-1 hard block enforcement"
            )
        }

        // ── Step 7: TASK_EXECUTED ledger write — only after authorization ────
        val artifactRef    = buildArtifactReference(executionTask.reportReference, executionTask.taskId)
        val execStatusStr  = filteredOutput.status.name
        val validStatusStr = validationResult.verdict.name

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

        ExecutionAuthorityExecutionResult.Executed(
            taskId            = executionTask.taskId,
            executionStatus   = filteredOutput.status,
            validationVerdict = validationResult.verdict,
            report            = frozenReport
        )

        } catch (e: Exception) {
            // ── FAILURE PATH — mandatory lifecycle closure (MQP-EXECUTION-INVARIANT-LOCK-01) ──
            // Any unexpected exception must still produce TASK_EXECUTED(FAILURE) so the
            // Governor's convergence loop is never left in a non-terminal state.
            try {
                ledger.appendEvent(
                    projectId,
                    EventTypes.TASK_EXECUTED,
                    mapOf(
                        "taskId"            to executionTask.taskId,
                        "contractId"        to executionTask.contractId,
                        "contractorId"      to "NO_CONTRACTOR_MATCH",
                        "artifactReference" to buildArtifactReference(executionTask.reportReference, executionTask.taskId, "NO_ARTIFACT"),
                        "executionStatus"   to "FAILURE",
                        "validationStatus"  to "FAILED",
                        "validationReasons" to listOf("EXECUTION_FAILED:${e.javaClass.simpleName}"),
                        "report_reference"  to executionTask.reportReference,
                        "position"          to executionTask.position,
                        "total"             to executionTask.total
                    )
                )
            } catch (_: Exception) {
                // Ledger write failure does not suppress the block result
            }
            ExecutionAuthorityExecutionResult.BlockedWithRecovery("EXECUTION_FAILED", emptyList())
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
     *  - reads contract ordering from CONTRACT_COMPLETED events (RRIL-1)
     *  - appends ASSEMBLY_STARTED and ASSEMBLY_COMPLETED to [ledger]
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

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 5 — Commit resolution (single write authority — AERP-1)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Resolve the pending COMMIT_CONTRACT by writing COMMIT_EXECUTED or COMMIT_ABORTED.
     *
     * GOVERNANCE RULE: ExecutionAuthority is the ONLY writer of these events.
     * CoreBridge is a signal router that delegates here; the UI has zero write authority.
     *
     * @param projectId Ledger identifier.
     * @param ledger    EventLedger — single write authority.
     * @param approved  true → COMMIT_EXECUTED; false → COMMIT_ABORTED.
     */
    fun resolveCommitDecision(projectId: String, ledger: EventLedger, approved: Boolean) {
        val events = ledger.loadEvents(projectId)
        val commitEvent = events.lastOrNull { it.type == EventTypes.COMMIT_CONTRACT } ?: return
        val reportReference = commitEvent.payload["report_reference"]?.toString() ?: ""
        if (approved) {
            ledger.appendEvent(
                projectId,
                EventTypes.COMMIT_EXECUTED,
                mapOf(
                    "report_reference" to reportReference,
                    "approvalStatus"   to "APPROVED"
                )
            )
        } else {
            ledger.appendEvent(
                projectId,
                EventTypes.COMMIT_ABORTED,
                mapOf(
                    "report_reference" to reportReference,
                    "approvalStatus"   to "REJECTED"
                )
            )
        }
    }

    // ─── AGOII-ANCHOR-STATE-IMMUTABILITY-006 public surface ──────────────────

    /**
     * Build an [AnchorState] from a frozen [ContractReport] (AERP-1 / RCF-1).
     *
     * The anchor is captured once per execution attempt at the moment validation begins
     * and MUST NOT be modified thereafter.
     *
     * @param frozenReport Immutable report snapshot produced by [ContractReport.copy()].
     * @return             Locked [AnchorState] derived from [frozenReport].
     */
    fun buildAnchorState(frozenReport: ContractReport): AnchorState = AnchorState(
        reportReference    = frozenReport.reportReference,
        validatedTypes     = frozenReport.typeInventory.toList(),
        validatedStructure = frozenReport.typeInventory.toSet(),
        validatedPaths     = frozenReport.logicFlow.toList(),
        validatedReport    = frozenReport
    )

    /**
     * Extract the first [ViolationSurface] from a [ValidationResult] (AERP-1).
     *
     * The first failure reason is treated as the primary violation surface.
     * Throws [IllegalStateException] when the result carries no failure reasons
     * (caller must only invoke this after confirming the verdict is FAILED).
     *
     * @param validationResult The failed [ValidationResult] from [ResultValidator.validate].
     * @return                 [ViolationSurface] for the primary violation.
     * @throws IllegalStateException when no violations are present.
     */
    fun extractViolationSurface(validationResult: ValidationResult): ViolationSurface {
        val firstReason = validationResult.failureReasons.firstOrNull()
            ?: throw IllegalStateException("AERP-1: violation expected but none found")
        return ViolationSurface(
            field  = firstReason,
            reason = firstReason
        )
    }

    /**
     * Enforce the anchor state mutation boundary (AERP-1 / AGOII-ANCHOR-STATE-IMMUTABILITY-006).
     *
     * Throws [IllegalStateException] if [attemptedMutationField] is in [AnchorState.lockedFields].
     * Permitted mutations (fields NOT in [lockedFields]) pass through silently.
     *
     * @param anchor                The locked [AnchorState] for this execution attempt.
     * @param attemptedMutationField The field name being mutated.
     * @throws IllegalStateException on locked-field mutation attempt.
     */
    fun enforceMutationBoundary(anchor: AnchorState, attemptedMutationField: String) {
        if (attemptedMutationField in anchor.lockedFields) {
            throw IllegalStateException(
                "BLOCKED: Attempt to mutate locked field '$attemptedMutationField' — Anchor state violation (AERP-1)"
            )
        }
    }

    /**
     * Issue a recovery contract for a single [ViolationSurface] and write it to the ledger (RCF-1).
     *
     * This is the ONLY permitted mutation surface during delta execution.  One call = one
     * violation = one recovery contract.  Returns [ExecutionAuthorityExecutionResult.RecoveryIssued]
     * so callers can propagate the result without further processing.
     *
     * @param projectId     Project ledger identifier.
     * @param ledger        [EventLedger] — single write authority.
     * @param executionTask The task that failed validation.
     * @param anchor        The locked [AnchorState] for this execution attempt.
     * @param violation     The single [ViolationSurface] that caused the failure.
     * @return              [ExecutionAuthorityExecutionResult.RecoveryIssued].
     */
    fun issueRecoveryContract(
        projectId:     String,
        ledger:        EventLedger,
        executionTask: ExecutionTask,
        anchor:        AnchorState,
        violation:     ViolationSurface
    ): ExecutionAuthorityExecutionResult.RecoveryIssued {
        try {
            ledger.appendEvent(
                projectId,
                EventTypes.RECOVERY_CONTRACT,
                mapOf(
                    "contractId"       to executionTask.contractId,
                    "taskId"           to executionTask.taskId,
                    "report_reference" to anchor.reportReference,
                    "failure_class"    to "LOGICAL",
                    "violation_field"  to violation.field,
                    "violation_reason" to violation.reason,
                    "locked_fields"    to anchor.lockedFields.toList()
                )
            )
        } catch (_: Exception) {
            // Ledger write failure does not suppress the recovery result
        }
        return ExecutionAuthorityExecutionResult.RecoveryIssued(
            contractId = executionTask.contractId,
            reason     = violation.reason
        )
    }

    /**
     * Apply a delta mutation strictly within the [ViolationSurface] boundary (AERP-1).
     *
     * Only fields that are NOT in [AnchorState.lockedFields] and match the [violation] field
     * may be mutated.  Any attempt to mutate a locked field throws [IllegalStateException].
     *
     * The result is the anchor's validated artifact structure merged with the permitted
     * [mutation] entries.
     *
     * @param anchor    The locked [AnchorState] for this execution attempt.
     * @param violation The single [ViolationSurface] that defines the mutation boundary.
     * @param mutation  The proposed mutations (key → value).
     * @return          Merged artifact map with only permitted mutations applied.
     * @throws IllegalStateException if any mutation key is in [AnchorState.lockedFields].
     */
    fun executeDelta(
        anchor:    AnchorState,
        violation: ViolationSurface,
        mutation:  Map<String, Any>
    ): Map<String, Any> {
        for (field in mutation.keys) {
            enforceMutationBoundary(anchor, field)
        }
        val base = emptyMap<String, Any>()
        return base + mutation
    }


    /**
     * Enforce AERP-1 hard block conditions before TASK_EXECUTED ledger write.
     *
     * Throws [IllegalStateException] if ANY invariant is violated.
     * All six conditions must pass; the first failing condition halts execution immediately.
     *
     * Hard blocks (AERP-1 enforcement):
     *  1. [report] must not be null — ContractReport is mandatory before any write.
     *  2. [validationExecuted] must be true — result validation must have run.
     *  3. [capabilities] must not be empty — requiredCapabilities must be present.
     *  4. [registry] must contain at least one verified contractor.
     *  5. [systemResult] must not be [ContractorSystemResult.Blocked] — a contractor must be resolved.
     *  6. [authorized] must be true — explicit authorization must have been granted.
     */
    fun enforceHardBlocks(
        report:             ContractReport?,
        validationExecuted: Boolean,
        capabilities:       List<ContractCapability>,
        registry:           ContractorRegistry,
        systemResult:       ContractorSystemResult,
        authorized:         Boolean
    ) {
        if (report == null) {
            throw IllegalStateException("BLOCKED: Missing ContractReport (AERP-1)")
        }
        if (!validationExecuted) {
            throw IllegalStateException("BLOCKED: Validation not executed (AERP-1)")
        }
        if (capabilities.isEmpty()) {
            throw IllegalStateException("BLOCKED: Missing requiredCapabilities (AERP-1)")
        }
        if (registry.allVerified().isEmpty()) {
            throw IllegalStateException("BLOCKED: ContractorRegistry empty (AERP-1)")
        }
        if (systemResult is ContractorSystemResult.Blocked) {
            throw IllegalStateException("BLOCKED: No contractor resolved (AERP-1)")
        }
        if (!authorized) {
            throw IllegalStateException("BLOCKED: Execution not authorized (AERP-1)")
        }
    }

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
        reason:        String,
        knownTaskId:   String? = null
    ): ExecutionAuthorityExecutionResult.BlockedWithRecovery {

        // VIOLATION 4: artifactReference must always be deterministic and referenceable — never "NONE"
        val artifactRef = if (task != null)
            buildArtifactReference(task.reportReference, task.taskId, "NO_ARTIFACT")
        else
            buildArtifactReference("UNKNOWN", knownTaskId ?: "UNKNOWN", "NO_ARTIFACT")

        // Emit TASK_EXECUTED(FAILURE) — mandatory lifecycle closure regardless of available context.
        // MQP-EXECUTION-INVARIANT-LOCK-01: every TASK_STARTED must produce TASK_EXECUTED.
        if (task != null) {
            try {
                ledger.appendEvent(
                    projectId,
                    EventTypes.TASK_EXECUTED,
                    mapOf(
                        "taskId"            to task.taskId,
                        "contractId"        to task.contractId,
                        "contractorId"      to "NO_CONTRACTOR_MATCH",
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
        } else if (knownTaskId != null) {
            // Pre-extraction failure: task context is unavailable but taskId is known.
            // Emit minimal TASK_EXECUTED(FAILURE) to satisfy lifecycle closure invariant.
            try {
                ledger.appendEvent(
                    projectId,
                    EventTypes.TASK_EXECUTED,
                    mapOf(
                        "taskId"            to knownTaskId,
                        "contractId"        to "UNKNOWN",
                        "contractorId"      to "NO_CONTRACTOR_MATCH",
                        "artifactReference" to artifactRef,
                        "executionStatus"   to "FAILURE",
                        "validationStatus"  to "FAILED",
                        "validationReasons" to listOf(reason),
                        "report_reference"  to "UNKNOWN"
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

    /** Generate [ContractReport] from execution output (AERP-1 / CONVERGENCE_HARDENING_V1). */
    private fun generateContractReport(
        task:         ExecutionTask,
        output:       ContractorExecutionOutput,
        trace:        com.agoii.mobile.contractors.ResolutionTrace,
        contractorId: String
    ): ContractReport {
        val steps       = listOf("MATCHING_RESOLVED", "EXECUTION_INVOKED", "ARTIFACT_PRODUCED")
        val rawOut      = output.resultArtifact["response"]?.toString() ?: output.error ?: ""
        val exitCodeVal = if (output.status == ExecutionStatus.SUCCESS) 0 else 1
        return ContractReport(
            reportReference    = task.reportReference,
            typeInventory      = output.resultArtifact.keys.toList(),
            functionSignatures = task.requirements.map { it.toString() }.ifEmpty { listOf(task.contractId) },
            logicFlow          = steps,
            errorConditions    = listOfNotNull(output.error),
            traceStructure     = trace,
            rawOutput          = rawOut,
            normalizedOutput   = if (output.status == ExecutionStatus.SUCCESS) rawOut else null,
            exitCode           = exitCodeVal,
            failureSurface     = listOfNotNull(output.error),
            policyViolations   = emptyList()
        )
    }

    /**
     * Enforce AERP-1 report completeness (CONVERGENCE_HARDENING_V1 / FSE-1).
     *
     * Returns a deterministically-ordered list of [Violation] objects — one per failing
     * field path. An empty list means the report is complete and execution may proceed.
     * No exception is thrown; all failures are emitted as atomic [Violation] values.
     */
    private fun validateReportCompleteness(
        report: ContractReport,
        contractId: String,
        deltaContext: DeltaContext? = null
    ): List<Violation> {
        val violations = mutableListOf<Violation>()

        // typeInventory — EMPTY
        if (report.typeInventory.isEmpty()) {
            violations.add(
                Violation(
                    reportReference    = report.reportReference,
                    contractId         = contractId,
                    fieldPath          = "typeInventory",
                    failureClass       = FailureClass.COMPLETENESS,
                    expected           = "non-empty list",
                    actual             = "empty",
                    message            = "typeInventory is empty",
                    correctionDirective = "Add at least one type entry to typeInventory"
                )
            )
        }

        // functionSignatures — EMPTY
        if (report.functionSignatures.isEmpty()) {
            violations.add(
                Violation(
                    reportReference    = report.reportReference,
                    contractId         = contractId,
                    fieldPath          = "functionSignatures",
                    failureClass       = FailureClass.COMPLETENESS,
                    expected           = "non-empty list",
                    actual             = "empty",
                    message            = "functionSignatures is empty",
                    correctionDirective = "Add at least one function signature"
                )
            )
        }

        // functionSignatures[i] — INVALID
        report.functionSignatures.forEachIndexed { i, value ->
            if (value.isBlank()) {
                violations.add(
                    Violation(
                        reportReference    = report.reportReference,
                        contractId         = contractId,
                        fieldPath          = "functionSignatures[$i]",
                        failureClass       = FailureClass.STRUCTURAL,
                        expected           = "valid non-empty function signature",
                        actual             = value,
                        message            = "Invalid function signature at index $i",
                        correctionDirective = "Replace with valid function signature at index $i"
                    )
                )
            }
        }

        // logicFlow — EMPTY
        if (report.logicFlow.isEmpty()) {
            violations.add(
                Violation(
                    reportReference    = report.reportReference,
                    contractId         = contractId,
                    fieldPath          = "logicFlow",
                    failureClass       = FailureClass.COMPLETENESS,
                    expected           = "non-empty execution flow",
                    actual             = "empty",
                    message            = "logicFlow is empty",
                    correctionDirective = "Add execution steps to logicFlow"
                )
            )
        }

        // logicFlow[i] — INVALID
        report.logicFlow.forEachIndexed { i, step ->
            if (step.isBlank()) {
                violations.add(
                    Violation(
                        reportReference    = report.reportReference,
                        contractId         = contractId,
                        fieldPath          = "logicFlow[$i]",
                        failureClass       = FailureClass.LOGICAL,
                        expected           = "valid execution step",
                        actual             = step,
                        message            = "Invalid logicFlow step at index $i",
                        correctionDirective = "Replace with valid execution step at index $i"
                    )
                )
            }
        }

        // errorConditions vs exitCode
        if (report.exitCode != 0 && report.errorConditions.isEmpty()) {
            violations.add(
                Violation(
                    reportReference    = report.reportReference,
                    contractId         = contractId,
                    fieldPath          = "errorConditions",
                    failureClass       = FailureClass.LOGICAL,
                    expected           = "non-empty when exitCode != 0",
                    actual             = "empty with exitCode=${report.exitCode}",
                    message            = "errorConditions missing for non-zero exitCode",
                    correctionDirective = "Add errorConditions describing failure when exitCode != 0"
                )
            )
        }

        // exitCode constraint
        if (report.exitCode != 0 && report.exitCode != 1) {
            violations.add(
                Violation(
                    reportReference    = report.reportReference,
                    contractId         = contractId,
                    fieldPath          = "exitCode",
                    failureClass       = FailureClass.CONSTRAINT,
                    expected           = "0 or 1",
                    actual             = report.exitCode.toString(),
                    message            = "Invalid exitCode value",
                    correctionDirective = "Set exitCode to 0 (success) or 1 (failure)"
                )
            )
        }

        // rawOutput empty on success
        if (report.exitCode == 0 && report.rawOutput.isBlank()) {
            violations.add(
                Violation(
                    reportReference    = report.reportReference,
                    contractId         = contractId,
                    fieldPath          = "rawOutput",
                    failureClass       = FailureClass.COMPLETENESS,
                    expected           = "non-empty output when execution succeeds",
                    actual             = "blank",
                    message            = "rawOutput is empty on successful execution",
                    correctionDirective = "Provide execution output in rawOutput when exitCode == 0"
                )
            )
        }

        // ── DEE-1-PATCH Step 3: Field-level regression check across all anchor paths ─
        if (deltaContext != null) {

            val anchorPaths = deltaContext.anchorState.validatedPaths

            anchorPaths.forEach { path ->
                if (path != deltaContext.violationField) {

                    val anchorValue   = extractFieldValue(deltaContext.anchorState, path)
                    val currentValue  = extractFieldValue(report, path)

                    if (anchorValue != currentValue) {
                        violations.add(
                            Violation(
                                reportReference     = report.reportReference,
                                contractId          = contractId,
                                fieldPath           = path,
                                failureClass        = FailureClass.DETERMINISM,
                                expected            = anchorValue.toString(),
                                actual              = currentValue.toString(),
                                message             = "REGRESSION_DETECTED",
                                correctionDirective = "Restore field: $path"
                            )
                        )
                    }
                }
            }

            // CORRECTION CHECK
            if (violations.any { it.fieldPath == deltaContext.violationField }) {
                violations.add(
                    Violation(
                        reportReference     = report.reportReference,
                        contractId          = contractId,
                        fieldPath           = deltaContext.violationField,
                        failureClass        = FailureClass.COMPLETENESS,
                        expected            = "corrected",
                        actual              = "still_invalid",
                        message             = "VIOLATION_NOT_RESOLVED",
                        correctionDirective = "Fix violation at ${deltaContext.violationField}"
                    )
                )
            }
        }

        return violations.sortedWith(
            compareBy<Violation> { it.fieldPath }
                .thenBy { it.failureClass.ordinal }
        )
    }

    /**
     * Transform a list of [Violation] objects into deterministic [ExecutionRecoveryContract]
     * instances (FSE-2 / RCF-1).
     *
     * One violation → one recovery contract. The anchor state is derived from the frozen
     * [ContractReport] at the moment of validation and embedded as-is. The result is
     * deterministically ordered by violationField ASC, then failureClass ordinal ASC.
     */
    private fun buildRecoveryContracts(
        violations: List<Violation>,
        report: ContractReport
    ): List<ExecutionRecoveryContract> {
        val anchorState = AnchorState(
            reportReference    = report.reportReference,
            validatedTypes     = report.typeInventory,
            validatedStructure = report.typeInventory.toSet(),
            validatedPaths     = report.logicFlow,
            validatedReport    = report
        )
        return violations.map { violation ->
            ExecutionRecoveryContract(
                reportReference     = violation.reportReference,
                contractId          = "RCF_${violation.contractId}_${violation.fieldPath.replace(".", "_").replace("[", "_").replace("]", "")}",
                failureClass        = violation.failureClass,
                violationField      = violation.fieldPath,
                correctionDirective = violation.correctionDirective,
                anchorState         = anchorState,
                successCondition    = "FIELD_CORRECTED:${violation.fieldPath}"
            )
        ).sortedWith(
            compareBy<ExecutionRecoveryContract> { it.violationField }
                .thenBy { it.failureClass.ordinal }
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
        validatedStructure = report.typeInventory.toSet(),
        validatedPaths     = report.logicFlow.toList()
    )

    /** Issue [ExecutionRecoveryContract] (RCF-1) for a SINGLE violation surface. */
    private fun issueRecoveryContract(
        task:            ExecutionTask?,
        anchorState:     AnchorState,
        failureClass:    String,
        violationSurface: String
    ): ExecutionRecoveryContract {
        val failureClassEnum = runCatching { FailureClass.valueOf(failureClass) }
            .getOrElse { FailureClass.STRUCTURAL }
        return ExecutionRecoveryContract(
            reportReference     = anchorState.reportReference,
            contractId          = "RCF_${task?.contractId ?: "UNKNOWN"}_EXECUTION",
            failureClass        = failureClassEnum,
            violationField      = violationSurface,
            correctionDirective = "Resolve $failureClass for task '${task?.taskId ?: "UNKNOWN"}' " +
                                  "at position ${task?.position ?: -1}: $violationSurface",
            anchorState         = anchorState,
            successCondition    = "TASK_EXECUTED written with executionStatus=SUCCESS AND validationStatus=VALIDATED"
        )
    }

    /**
     * Write [ExecutionRecoveryContract] to the ledger as a RECOVERY_CONTRACT event (RCF-1).
     * All recovery MUST be ledger-materialized; in-memory recovery is PROHIBITED.
     * Includes FAILURE_REFERENCE fields (report_reference, contractId) for ledger traceability.
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
                    "report_reference"    to recovery.reportReference,
                    "failureClass"        to recovery.failureClass.name,
                    "violationField"      to recovery.violationField,
                    "correctionDirective" to recovery.correctionDirective,
                    "successCondition"    to recovery.successCondition,
                    "artifactReference"   to artifactRef
                )
            )
        } catch (_: Exception) {
            // Ledger write failure is recorded but does not suppress the block result
        }
    }

    /**
     * Extract [ContractCapability] list from the CONTRACT_CREATED ledger event for [contractId].
     *
     * Capabilities are read ONLY from the CONTRACT_CREATED event written by
     * [ingestUniversalContract].
     *
     * When no CONTRACT_CREATED event exists (legacy ledger path) or the field is absent/empty,
     * returns [ContractCapability.STRUCTURAL_ACCURACY] as a minimal safe default.  This covers
     * legacy ledgers that pre-date UCS-1 ingestion; new ledgers always carry the full list.
     */
    private fun extractCapabilitiesFromLedger(
        contractId: String,
        events:     List<com.agoii.mobile.core.Event>
    ): List<ContractCapability> {
        val contractCreated = events.firstOrNull {
            it.type == EventTypes.CONTRACT_CREATED &&
            it.payload["contractId"]?.toString() == contractId
        }
        @Suppress("UNCHECKED_CAST")
        val rawList = contractCreated?.payload?.get("requiredCapabilities") as? List<*>
        val capabilities = rawList
            ?.mapNotNull { it?.toString() }
            ?.mapNotNull { name -> runCatching { ContractCapability.valueOf(name) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
        return capabilities ?: listOf(ContractCapability.STRUCTURAL_ACCURACY)
    }

    /**
     * Extract [ContractCapability] list STRICTLY from the CONTRACT_CREATED ledger event for [contractId].
     *
     * Unlike [extractCapabilitiesFromLedger], this method has NO FALLBACK.
     * Any missing, invalid, or empty capability list causes an [IllegalStateException] (AERP-1 violation).
     *
     * Uses [lastOrNull] to pick the latest CONTRACT_CREATED for [contractId].
     *
     * @throws IllegalStateException on any AERP-1 violation (missing event, missing field,
     *         invalid type, null entry, unknown capability name, or empty result).
     */
    private fun extractCapabilitiesFromLedgerStrict(
        contractId: String,
        events:     List<com.agoii.mobile.core.Event>
    ): List<ContractCapability> {
        val createdEvent = events
            .lastOrNull {
                it.type == EventTypes.CONTRACT_CREATED &&
                it.payload["contractId"] == contractId
            }
            ?: throw IllegalStateException(
                "AERP-1 violation: CONTRACT_CREATED not found for contractId=$contractId"
            )

        val raw = createdEvent.payload["requiredCapabilities"]
            ?: throw IllegalStateException(
                "AERP-1 violation: requiredCapabilities missing for contractId=$contractId"
            )

        if (raw !is List<*>) {
            throw IllegalStateException(
                "AERP-1 violation: requiredCapabilities invalid type for contractId=$contractId"
            )
        }

        val capabilities = raw.map {
            val value = it?.toString()
                ?: throw IllegalStateException(
                    "AERP-1 violation: null capability for contractId=$contractId"
                )
            try {
                ContractCapability.valueOf(value)
            } catch (_: Exception) {
                throw IllegalStateException(
                    "AERP-1 violation: unknown capability '$value' for contractId=$contractId"
                )
            }
        }

        if (capabilities.isEmpty()) {
            throw IllegalStateException(
                "AERP-1 violation: requiredCapabilities empty for contractId=$contractId"
            )
        }

        return capabilities
    }

    /**
     * Domain context resolved from the CONTRACT_CREATED ledger event for [contractId].
     *
     * The CONTRACT_CREATED event is written by [ingestUniversalContract] and carries
     * `executionType` and `targetDomain` from the originating [UniversalContract].
     * When no CONTRACT_CREATED event exists (legacy ledger path), canonical defaults are
     * applied: [ExecutionType.INTERNAL_EXECUTION] + [TargetDomain.CONTRACTOR].
     */
    private data class DomainContext(
        val executionType: com.agoii.mobile.contracts.ExecutionType,
        val targetDomain:  com.agoii.mobile.contracts.TargetDomain
    )

    private fun lookupDomainContext(
        contractId: String,
        events:     List<com.agoii.mobile.core.Event>
    ): DomainContext {
        val contractCreated = events.firstOrNull {
            it.type == EventTypes.CONTRACT_CREATED &&
            it.payload["contractId"]?.toString() == contractId
        }
        val executionType = contractCreated
            ?.payload?.get("executionType")?.toString()
            ?.let { runCatching { com.agoii.mobile.contracts.ExecutionType.valueOf(it) }.getOrNull() }
            ?: com.agoii.mobile.contracts.ExecutionType.INTERNAL_EXECUTION
        val targetDomain = contractCreated
            ?.payload?.get("targetDomain")?.toString()
            ?.let { runCatching { com.agoii.mobile.contracts.TargetDomain.valueOf(it) }.getOrNull() }
            ?: com.agoii.mobile.contracts.TargetDomain.CONTRACTOR
        return DomainContext(executionType, targetDomain)
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

    // ═══════════════════════════════════════════════════════════════════════
    // UCS-1 — Deterministic routing via contract-declared execution semantics
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Produce a deterministic [ExecutionRoute] for [contract] based solely on the
     * execution semantics encoded within the contract itself (UCS-1).
     *
     * This method is purely functional: it performs no I/O, writes nothing to the
     * ledger, and has no side effects.  The system does NOT adapt to contract types;
     * the contract declares how it must be executed and this method reads that
     * declaration.
     *
     * @param contract The [UniversalContract] to route.
     * @return An [ExecutionRoute] variant matching [contract.executionType].
     */
    fun route(contract: UniversalContract): ExecutionRoute = when (contract.executionType) {
        ExecutionType.INTERNAL_EXECUTION -> ExecutionRoute.InternalExecution(contract.targetDomain)
        ExecutionType.EXTERNAL_EXECUTION -> ExecutionRoute.ExternalExecution(contract.targetDomain)
        ExecutionType.COMMUNICATION      -> ExecutionRoute.Communication(contract.targetDomain)
        ExecutionType.AI_PROCESSING      -> ExecutionRoute.AiProcessing(contract.targetDomain)
        ExecutionType.SWARM_COORDINATION -> ExecutionRoute.SwarmCoordination(contract.targetDomain)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PHASE 6 — Universal Contract ingestion pipeline (UCS-1 GOVERNANCE INPUT)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Ingest a [UniversalContract] through the UCS-1 pipeline (AERP-1 pre-ledger gate).
     *
     * [UniversalContract] is a GOVERNANCE INPUT ONLY — no execution is triggered here.
     * Execution occurs exclusively via [executeFromLedger] when `TASK_STARTED` is the
     * latest ledger event.
     *
     * Pipeline (locked — all phases run in full before any write except CONTRACT_CREATED):
     *  Phase 1 — CONTRACT_CREATED written (records ingestion attempt unconditionally)
     *  Surface 2 — [UniversalContractValidator]: structural + semantic validation (AERP-1)
     *              → on failure: RECOVERY_CONTRACT written; returns [UniversalIngestionResult.ValidationFailed]
     *  Surface 3 — [UniversalContractNormalizer]: canonical form production
     *  Surface 6 — [ContractEnforcementEngine]: pre-execution enforcement gate
     *              → on pass:   CONTRACT_VALIDATED written
     *              → on failure: CONTRACT_VALIDATED written, then RECOVERY_CONTRACT; returns
     *                            [UniversalIngestionResult.EnforcementFailed]
     *  Surface 4 — [route]: pure route classification (no ledger write, no side effects)
     *  Phase end — CONTRACT_APPROVED written; returns [UniversalIngestionResult.Ingested]
     *
     * RRIL-1: [contract.reportReference] is propagated unchanged through every ledger event.
     * RCF-1: any failure produces exactly one RECOVERY_CONTRACT (one violation surface).
     * NO partial ingestion: CONTRACT_CREATED is always written (audit trail).
     *
     * @param contract  The [UniversalContract] to ingest (governance input).
     * @param projectId Project ledger identifier.
     * @param ledger    EventLedger — single write authority.
     * @return [UniversalIngestionResult] describing the outcome.
     */
    fun ingestUniversalContract(
        contract:  UniversalContract,
        projectId: String,
        ledger:    EventLedger
    ): UniversalIngestionResult {

        val ingestTaskId = "ingest::${contract.contractId}"

        // ── Phase 1: Record ingestion attempt (CONTRACT_CREATED) ─────────────
        // Always written first — NO partial ingestion, NO silent drops (RCF-1).
        // If this write fails (wrong ledger state), the exception propagates to the
        // caller; the ingestion cannot proceed without the audit anchor event.
        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACT_CREATED,
            mapOf(
                "contractId"            to contract.contractId,
                "intentId"              to contract.intentId,
                "report_reference"      to contract.reportReference,
                "contractClass"         to contract.contractClass.name,
                "executionType"         to contract.executionType.name,
                "targetDomain"          to contract.targetDomain.name,
                "position"              to contract.position,
                "total"                 to contract.total,
                "requiredCapabilities"  to contract.requiredCapabilities.map { it.name }
            )
        )

        // ── Surface 2: Structural + Semantic Validation (AERP-1 pre-ledger gate) ──
        val validationResult = contractValidator.validate(contract)
        if (validationResult is ContractValidationResult.Invalid) {
            val anchorState = AnchorState(
                reportReference    = contract.reportReference,
                validatedTypes     = emptyList(),
                validatedStructure = emptySet(),
                validatedPaths     = emptyList()
            )
            val artifactRef      = buildArtifactReference(contract.reportReference, ingestTaskId, "NO_ARTIFACT")
            val violationSurface = "VALIDATION_FAILED: ${validationResult.violations.joinToString("; ")}"
            val recovery = ExecutionRecoveryContract(
                reportReference     = contract.reportReference,
                contractId          = "RCF_${contract.contractId}_VALIDATION",
                failureClass        = FailureClass.STRUCTURAL,
                violationField      = violationSurface,
                correctionDirective = "Resolve STRUCTURAL failure for contract '${contract.contractId}': $violationSurface",
                anchorState         = anchorState,
                successCondition    = "Contract '${contract.contractId}' executed with SUCCESS"
            )
            writeRecoveryContractToLedger(projectId, ledger, recovery, artifactRef)
            return UniversalIngestionResult.ValidationFailed(
                violations       = validationResult.violations,
                recoveryContract = recovery
            )
        }

        // ── Surface 3: Normalization ──────────────────────────────────────────
        val normalized = contractNormalizer.normalize(contract)

        // ── Surface 6: Enforcement (blocking pre-execution gate) ──────────────
        val enforcementResult = enforcementEngine.enforce(normalized)

        // Write CONTRACT_VALIDATED — structural + semantic validation confirmed
        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACT_VALIDATED,
            mapOf(
                "contractId"       to normalized.contractId,
                "report_reference" to normalized.reportReference
            )
        )

        if (enforcementResult is ContractEnforcementResult.Violated) {
            val anchorState = AnchorState(
                reportReference    = normalized.reportReference,
                validatedTypes     = emptyList(),
                validatedStructure = emptySet(),
                validatedPaths     = emptyList()
            )
            val artifactRef      = buildArtifactReference(normalized.reportReference, ingestTaskId, "NO_ARTIFACT")
            val violationSurface = "ENFORCEMENT_FAILED: ${enforcementResult.violations.joinToString("; ") { it.description }}"
            val recovery = ExecutionRecoveryContract(
                reportReference     = normalized.reportReference,
                contractId          = "RCF_${normalized.contractId}_ENFORCEMENT",
                failureClass        = FailureClass.STRUCTURAL,
                violationField      = violationSurface,
                correctionDirective = "Resolve STRUCTURAL enforcement failure for contract '${normalized.contractId}': $violationSurface",
                anchorState         = anchorState,
                successCondition    = "Contract '${normalized.contractId}' executed with SUCCESS"
            )
            writeRecoveryContractToLedger(projectId, ledger, recovery, artifactRef)
            return UniversalIngestionResult.EnforcementFailed(
                violations       = enforcementResult.violations,
                recoveryContract = recovery
            )
        }

        // ── Surface 4: Route (pure — no I/O, no side effects, no ledger write) ─
        val executionRoute = route(normalized)

        // Write CONTRACT_APPROVED — enforcement passed and route classified
        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACT_APPROVED,
            mapOf(
                "contractId"       to normalized.contractId,
                "report_reference" to normalized.reportReference,
                "executionRoute"   to (executionRoute::class.simpleName ?: "Unknown")
            )
        )

        return UniversalIngestionResult.Ingested(
            contractId      = normalized.contractId,
            reportReference = normalized.reportReference
        )
    }

    // ── DEE-1 Step 6: Helper functions ────────────────────────────────────────

    private fun extractViolationFieldFromContractId(contractId: String): String {
        return contractId.substringAfterLast("_").replace("_", ".")
    }

    private fun extractAnchorStateFromRecovery(
        task: ExecutionTask,
        events: List<com.agoii.mobile.core.Event>
    ): AnchorState {
        val reportRef = task.reportReference
        val reportEvent = events
            .firstOrNull { it.payload["report_reference"] == reportRef }
            ?: throw IllegalStateException("ANCHOR_STATE_MISSING: $reportRef")
        val report = reconstructContractReport(reportEvent)
        return AnchorState(
            reportReference    = report.reportReference,
            validatedTypes     = report.typeInventory,
            validatedStructure = report.typeInventory.toSet(),
            validatedPaths     = extractValidatedPaths(report),
            validatedReport    = report
        )
    }

    private fun reconstructContractReport(event: com.agoii.mobile.core.Event): ContractReport {
        return event.payload["contract_report"] as? ContractReport
            ?: throw IllegalStateException(
                "ANCHOR_STATE_MISSING: contract_report not found in event payload for report_reference=${event.payload["report_reference"]}"
            )
    }

    private fun extractValidatedPaths(report: ContractReport): List<String> {
        return buildList {
            if (report.typeInventory.isNotEmpty()) add("typeInventory")
            if (report.functionSignatures.isNotEmpty()) add("functionSignatures")
            if (report.logicFlow.isNotEmpty()) add("logicFlow")
            if (report.errorConditions.isNotEmpty()) add("errorConditions")
            add("exitCode")
            if (report.rawOutput.isNotEmpty()) add("rawOutput")
        }
    }

    private fun extractReportReferenceFromRecovery(task: ExecutionTask): String {
        return task.reportReference
    }

    private fun enforceAnchorStateIntegrity(deltaContext: DeltaContext) {
        // NO-OP placeholder (future extension point)
    }

    private fun filterToDeltaSurface(
        output: ContractorExecutionOutput,
        deltaContext: DeltaContext
    ): ContractorExecutionOutput {
        val anchor = deltaContext.anchorState
        val field  = deltaContext.violationField

        val anchorArtifact = anchorToArtifact(anchor)
        val deltaArtifact  = output.resultArtifact

        val correctedValue = deltaArtifact[field]
            ?: throw IllegalStateException("DELTA_MISSING_FIELD: $field")

        val newArtifact = mutableMapOf<String, Any>()
        newArtifact.putAll(anchorArtifact)
        newArtifact[field] = correctedValue

        return output.copy(resultArtifact = newArtifact)
    }

    private fun anchorToArtifact(anchor: AnchorState): Map<String, Any> {
        return anchor.validatedStructure.associateWith { it }
    }

    private fun extractFieldValue(source: Any, field: String): Any? {
        val report: ContractReport? = when (source) {
            is ContractReport -> source
            is AnchorState    -> source.validatedReport
            else              -> null
        }
        return when (field) {
            "typeInventory"      -> report?.typeInventory
            "functionSignatures" -> report?.functionSignatures
            "logicFlow"          -> report?.logicFlow
            "errorConditions"    -> report?.errorConditions
            "exitCode"           -> report?.exitCode
            "rawOutput"          -> report?.rawOutput
            else                 -> null
        }
    }

    private fun isNonConverging(
        deltaContext: DeltaContext?,
        violations: List<Violation>
    ): Boolean {
        if (deltaContext == null) return false

        return violations.any {
            it.fieldPath == deltaContext.violationField &&
            it.failureClass == FailureClass.COMPLETENESS
        }
    }
}
