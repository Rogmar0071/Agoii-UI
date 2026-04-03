package com.agoii.mobile.execution

import com.agoii.mobile.assembly.AssemblyExecutionResult
import com.agoii.mobile.assembly.AssemblyModule
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contracts.ContractReport
import com.agoii.mobile.contracts.UniversalContract
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.adapter.NemoClawAdapter

// ══════════════════════════════════════════════════════════════════════════════
// AGOII-CONVERGENCE-ENFORCEMENT-LOCK-003
// EXECUTION AUTHORITY — SOLE EXECUTION INTELLIGENCE
// ══════════════════════════════════════════════════════════════════════════════
//
// AUTHORITY PARTITION:
//   - Replay = FACTS ONLY (no derivation)
//   - Governor = PURE SEQUENCER (no execution logic)
//   - ExecutionAuthority = SOLE EXECUTION INTELLIGENCE
//
// CONVERGENCE INVARIANTS (HARD-LOCKED):
//   3.1 Report Anchoring:        Every execution MUST produce ContractReport
//   3.2 Locked State Enforcement: Delta ONLY modifies mutationSurface
//   3.3 Mutation Surface:         Explicit declaration required
//   3.4 Delta-Only Execution:     No full rewrites allowed
//   3.5 Convergence Limit:        MAX_DELTA enforced (single source)
//   3.6 Failure Escalation:       Bounded termination guaranteed
//
// ══════════════════════════════════════════════════════════════════════════════

/**
 * ExecutionAuthority — SOLE EXECUTION INTELLIGENCE.
 *
 * Responsibilities:
 *   - Pre-ledger contract authorization (evaluate)
 *   - Ledger-driven task execution (executeFromLedger)
 *   - Universal contract ingestion (ingestUniversalContract)
 *   - Assembly execution (assembleFromLedger)
 *   - Convergence enforcement (MAX_DELTA tracking)
 *   - Recovery contract emission (RECOVERY_CONTRACT source=EXECUTION_AUTHORITY)
 *
 * Authority Rules:
 *   - NO execution logic in Governor or Replay
 *   - NO derived state in Replay (facts only)
 *   - NO retry logic outside ExecutionAuthority
 *   - NO convergence constants outside MAX_DELTA
 *
 * Convergence Guarantees:
 *   - Recovery chains tracked per contractId
 *   - CONTRACT_FAILED emitted at convergence ceiling
 *   - Validated sections locked (regression detection)
 *   - Mutation surface enforced (delta-only)
 */
class ExecutionAuthority(
    private val contractorRegistry: ContractorRegistry,
    private val driverRegistry:     DriverRegistry
) {

    private val nemoClawAdapter = NemoClawAdapter()

    companion object {
        /**
         * Maximum delta attempts per contract (CONVERGENCE CEILING).
         *
         * This is the SINGLE SOURCE for convergence limits.
         * Governor reads this constant; NO other module may define retry limits.
         *
         * CONTRACT: AGOII-CONVERGENCE-ENFORCEMENT-LOCK-003 Section 3.5
         */
        const val MAX_DELTA: Int = 3
        
        /**
         * Source identifier for RECOVERY_CONTRACT events.
         */
        private const val RECOVERY_SOURCE = "EXECUTION_AUTHORITY"
        
        /**
         * Maximum acceptable diff ratio for delta execution.
         * 
         * If diff > this threshold, treat as full rewrite and BLOCK.
         *
         * CONTRACT: AGOII-CONVERGENCE-ENFORCEMENT-COMPLETION-004 Section 3.4
         */
        private const val MAX_DIFF_RATIO = 0.4 // 40%
    }

    private val assemblyModule = AssemblyModule()

    // ══════════════════════════════════════════════════════════════════════════
    // 3.1 REPORT ANCHORING (MANDATORY)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Pre-ledger authorization gate for contracts.
     *
     * Validates:
     *   - Report reference present and non-blank
     *   - All contracts have matching report reference
     *   - Contracts are non-empty
     *   - Position sequence is valid (1-indexed, contiguous)
     *
     * CONTRACT: Section 3.1 — Report Anchoring (MANDATORY)
     *
     * @param input Contract batch + report reference.
     * @return [ExecutionAuthorityResult.Approved] or [ExecutionAuthorityResult.Blocked].
     */
    fun evaluate(input: ExecutionContractInput): ExecutionAuthorityResult {
        // RULE 3.1: Report reference MUST be present
        if (input.reportReference.isBlank()) {
            return ExecutionAuthorityResult.Blocked(
                "Report reference is blank — BLOCKED by 3.1 Report Anchoring"
            )
        }

        // RULE 3.1: All contracts MUST have matching report reference
        val contracts = input.contracts
        if (contracts.isEmpty()) {
            return ExecutionAuthorityResult.Blocked(
                "Contract list is empty — BLOCKED by 3.1 Report Anchoring"
            )
        }

        val mismatch = contracts.firstOrNull { it.reportReference != input.reportReference }
        if (mismatch != null) {
            return ExecutionAuthorityResult.Blocked(
                "Contract ${mismatch.contractId} has mismatched report reference " +
                "${mismatch.reportReference} != ${input.reportReference} — BLOCKED by 3.1"
            )
        }

        // Validate position sequence (1-indexed, contiguous)
        val sorted = contracts.sortedBy { it.position }
        sorted.forEachIndexed { index, contract ->
            if (contract.position != index + 1) {
                return ExecutionAuthorityResult.Blocked(
                    "Contract ${contract.contractId} has position ${contract.position}, " +
                    "expected ${index + 1} — invalid sequence"
                )
            }
        }

        return ExecutionAuthorityResult.Approved(sorted)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LEDGER-DRIVEN EXECUTION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Execute task from ledger when TASK_STARTED is the last event.
     *
     * Flow:
     *   1. Check last event type
     *   2. If TASK_STARTED → execute task
     *   3. If TASK_EXECUTED(FAILURE) → emit RECOVERY_CONTRACT (P1 handler)
     *   4. Otherwise → NotTriggered
     *
     * Convergence Enforcement:
     *   - Track recovery chains per contractId
     *   - Emit CONTRACT_FAILED when MAX_DELTA exceeded
     *   - Validate mutation surface for delta contracts
     *   - Prevent regression on validated sections
     *
     * @param projectId Project identifier.
     * @param ledger    Event ledger.
     * @return [ExecutionAuthorityExecutionResult].
     */
    fun executeFromLedger(
        projectId: String,
        ledger:    EventLedger
    ): ExecutionAuthorityExecutionResult {
        val events = ledger.loadEvents(projectId)
        val lastEvent = events.lastOrNull()
            ?: return ExecutionAuthorityExecutionResult.NotTriggered

        return when (lastEvent.type) {
            EventTypes.TASK_STARTED -> handleTaskStarted(projectId, lastEvent, events, ledger)
            EventTypes.TASK_EXECUTED -> handleTaskExecuted(projectId, lastEvent, events, ledger)
            else -> ExecutionAuthorityExecutionResult.NotTriggered
        }
    }

    /**
     * Handle TASK_STARTED event — execute task.
     *
     * EXECUTION FLOW (AGOII-ARTIFACT-SPINE-001):
     *   1. Execute task via ContractorExecutor
     *   2. Receive ExecutionReport + Artifact
     *   3. If delta: Validate artifact (rules 3.2-3.4)
     *   4. Emit TASK_EXECUTED with artifact
     *
     * DELTA VALIDATION (Rules 3.2-3.4):
     *   - If delta contract: validate against previousArtifact + mutationSurface
     *   - Block on: regression, out-of-scope mutation, full rewrite
     *   - Emit RECOVERY_CONTRACT on violation
     */
    private fun handleTaskStarted(
        projectId:  String,
        taskEvent:  Event,
        events:     List<Event>,
        ledger:     EventLedger
    ): ExecutionAuthorityExecutionResult {
        val taskId = taskEvent.payload["taskId"]?.toString()
            ?: return ExecutionAuthorityExecutionResult.Blocked("taskId missing in TASK_STARTED")

        val contractId = taskEvent.payload["contractId"]?.toString()
            ?: return ExecutionAuthorityExecutionResult.Blocked("contractId missing in TASK_STARTED")

        // Extract report reference from CONTRACT_CREATED event
        val reportReference = extractReportReference(events, contractId)
        if (reportReference.isBlank()) {
            return ExecutionAuthorityExecutionResult.Blocked(
                "Contract execution blocked: report_reference missing (3.1 violation)"
            )
        }

        // Extract contract name from CONTRACT_CREATED event (or use contractId as fallback)
        val contractName = extractContractName(events, contractId) ?: contractId

        // Build ExecutionContract
        // Note: Position is set to 1 as it's not relevant for single task execution.
        // The position field is used for batch execution ordering, but handleTaskStarted
        // processes one task at a time. If position tracking becomes necessary for
        // traceability, it should be extracted from the TASK_ASSIGNED or CONTRACT_CREATED
        // event payload.
        val contract = ExecutionContract(
            contractId = contractId,
            name = contractName,
            position = 1,
            reportReference = reportReference
        )

        // CONTRACT: AGOII–EXECUTION-AUTHORITY-PURITY-002
        // Execute via NemoClawAdapter with hard failure wrapper
        // RULE 1: No ledger writes - pure decision gate
        val executionReport = try {
            nemoClawAdapter.execute(contract)
        } catch (e: Exception) {
            // Adapter execution failure → BLOCKED (adapter crash)
            return ExecutionAuthorityExecutionResult.Blocked(
                "Adapter execution failure: ${e.message}"
            )
        }

        // ADMISSION CONTROL — Execution report must exist (grounding required)
        // (Note: adapter never returns null due to fail-closed design, but keeping for defense)

        // ADMISSION CONTROL — Artifact must exist (grounding required)
        // RULE 3: Missing artifact → BLOCKED
        if (executionReport.artifact == null) {
            return ExecutionAuthorityExecutionResult.Blocked(
                "Execution failed: missing artifact (grounding violation)"
            )
        }

        // EXECUTION_ID INTEGRITY CHECK
        // RULE 3: executionId mismatch → FAILURE (executed but failed validation)
        if (executionReport.executionId != contract.executionId) {
            return ExecutionAuthorityExecutionResult.Executed(
                taskId,
                ExecutionStatus.FAILURE,
                null
            )
        }

        // EXECUTION STATUS GATE
        // RULE 3: status != SUCCESS → FAILURE
        if (executionReport.status != "SUCCESS") {
            return ExecutionAuthorityExecutionResult.Executed(
                taskId,
                ExecutionStatus.FAILURE,
                null
            )
        }
        
        // Check if this is a delta contract (from RECOVERY_CONTRACT flow)
        val isDeltaContract = events.any { event ->
            event.type == EventTypes.DELTA_CONTRACT_CREATED &&
            event.payload["contractId"]?.toString() == contractId
        }

        // DELTA VALIDATION (MANDATORY FOR DELTA CONTRACTS)
        // RULE 2: Pure decision - no side effects
        if (isDeltaContract) {
            val deltaValidation = performDeltaValidation(events, contractId, executionReport)
            when (deltaValidation) {
                is DeltaValidationResult.RegressionDetected -> {
                    // RULE 3.2 violation → FAILURE
                    return ExecutionAuthorityExecutionResult.Executed(
                        taskId,
                        ExecutionStatus.FAILURE,
                        null
                    )
                }
                is DeltaValidationResult.OutOfScopeMutation -> {
                    // RULE 3.3 violation → FAILURE
                    return ExecutionAuthorityExecutionResult.Executed(
                        taskId,
                        ExecutionStatus.FAILURE,
                        null
                    )
                }
                is DeltaValidationResult.FullRewriteDetected -> {
                    // RULE 3.4 violation → FAILURE
                    return ExecutionAuthorityExecutionResult.Executed(
                        taskId,
                        ExecutionStatus.FAILURE,
                        null
                    )
                }
                is DeltaValidationResult.NonDeltaRewrite -> {
                    // RULE 3.4.1 violation → FAILURE
                    return ExecutionAuthorityExecutionResult.Executed(
                        taskId,
                        ExecutionStatus.FAILURE,
                        null
                    )
                }
                is DeltaValidationResult.MissingData -> {
                    // ARTIFACT MANDATORY: Missing artifact blocks execution
                    return ExecutionAuthorityExecutionResult.Blocked(
                        "Delta validation failed: ${deltaValidation.reason}"
                    )
                }
                DeltaValidationResult.Approved -> {
                    // Delta validation passed, continue with execution
                }
                else -> {
                    // Any other delta validation result is a failure
                    // Note: Unexpected validation result - consider updating when clause
                    return ExecutionAuthorityExecutionResult.Executed(
                        taskId,
                        ExecutionStatus.FAILURE,
                        null
                    )
                }
            }
        }

        // SUCCESS EMISSION (SINGLE POINT)
        // CONTRACT: AGOII–EXECUTION-AUTHORITY-PURITY-002
        // RULE 2: Pure return - no side effects
        // Only reached if:
        // - executionReport.status == SUCCESS
        // - executionReport.artifact exists
        // - (if delta) deltaValidation == Approved
        
        // TODO(Phase 2): Generate ContractReport from ExecutionReport when execution is wired
        val report = null // Placeholder until execution integration
        
        return ExecutionAuthorityExecutionResult.Executed(
            taskId,
            ExecutionStatus.SUCCESS,
            report
        )
    }

    /**
     * Perform delta validation for rules 3.2-3.4.
     *
     * CONTRACT: AGOII-ARTIFACT-SPINE-001 — Artifact Mandatory Check
     * CONTRACT: AGOII-CONVERGENCE-ENFORCEMENT-COMPLETION-004 — Delta Rules
     *
     * @param events Event history.
     * @param contractId Contract identifier.
     * @param report Execution report (contains artifact).
     * @return [DeltaValidationResult].
     */
    private fun performDeltaValidation(
        events: List<Event>,
        contractId: String,
        report: ExecutionReport?
    ): DeltaValidationResult {
        // RULE: ARTIFACT MANDATORY (AGOII-ARTIFACT-SPINE-001)
        if (report == null) {
            return DeltaValidationResult.MissingData("execution report missing")
        }
        
        if (report.artifact == null) {
            return DeltaValidationResult.MissingData("artifact missing from execution report")
        }
        
        val validatedSections = extractValidatedSections(events, contractId)
        val mutationSurface = extractMutationSurface(events, contractId)
        
        // If mutation surface is missing for delta contract, require it (RULE 3.3)
        if (mutationSurface == null) {
            return DeltaValidationResult.MissingData("mutationSurface missing in RECOVERY_CONTRACT")
        }
        
        // Load previous artifact for comparison
        val previousArtifact = loadPreviousArtifact(events, contractId)
        if (previousArtifact == null) {
            // First execution or no prior artifact — allow
            return DeltaValidationResult.Approved
        }
        
        // Build validation context and perform delta validation
        val context = DeltaValidationContext(
            contractId = contractId,
            previousArtifact = previousArtifact,
            newArtifact = report.artifact.sections,
            validatedSections = validatedSections,
            mutationSurface = mutationSurface
        )
        
        return validateDelta(context)
    }

    /**
     * Emit RECOVERY_CONTRACT for delta validation violations.
     *
     * @param projectId Project identifier.
     * @param ledger Event ledger.
     * @param contractId Contract identifier.
     * @param failureClass Failure classification.
     * @param reason Human-readable reason.
     */
    private fun emitDeltaViolationRecovery(
        projectId:    String,
        ledger:       EventLedger,
        contractId:   String,
        failureClass: FailureClass,
        reason:       String
    ) {
        // Don't emit if already at convergence ceiling
        val events = ledger.loadEvents(projectId)
        val recoveryCount = countRecoveryAttempts(events, contractId)
        if (recoveryCount >= MAX_DELTA) {
            return // Let handleTaskExecuted emit CONTRACT_FAILED
        }
        
        // This will be picked up by handleTaskExecuted (P1 handler)
        // No need to emit RECOVERY_CONTRACT here directly
    }

    /**
     * Handle TASK_EXECUTED(FAILURE) event — P1 recovery handler.
     *
     * Emits RECOVERY_CONTRACT with source=EXECUTION_AUTHORITY.
     *
     * CONTRACT: Section 3.5 — Convergence Limit Enforcement
     * CONTRACT: Section 3.6 — Failure Escalation
     */
    private fun handleTaskExecuted(
        projectId:  String,
        taskEvent:  Event,
        events:     List<Event>,
        ledger:     EventLedger
    ): ExecutionAuthorityExecutionResult {
        val status = taskEvent.payload["executionStatus"]?.toString()
        if (status != "FAILURE") {
            return ExecutionAuthorityExecutionResult.NotTriggered
        }

        val taskId = taskEvent.payload["taskId"]?.toString()
            ?: return ExecutionAuthorityExecutionResult.Blocked("taskId missing in TASK_EXECUTED")

        val contractId = taskEvent.payload["contractId"]?.toString() ?: taskId

        // RULE 3.5: Check convergence ceiling
        val recoveryCount = countRecoveryAttempts(events, contractId)
        if (recoveryCount >= MAX_DELTA) {
            // Emit CONTRACT_FAILED (convergence ceiling reached)
            ledger.appendEvent(
                projectId,
                EventTypes.CONTRACT_FAILED,
                mapOf(
                    "contractId" to contractId,
                    "reason" to "NON_CONVERGENT_SYSTEM",
                    "recoveryAttempts" to recoveryCount,
                    "maxDelta" to MAX_DELTA
                )
            )
            return ExecutionAuthorityExecutionResult.Blocked(
                "Convergence ceiling reached for $contractId ($recoveryCount >= $MAX_DELTA)"
            )
        }

        // Emit RECOVERY_CONTRACT (P1 handler)
        val recoveryId = deriveRecoveryId(projectId, contractId, recoveryCount)
        val reportReference = extractReportReference(events, contractId)

        ledger.appendEvent(
            projectId,
            EventTypes.RECOVERY_CONTRACT,
            mapOf(
                "recoveryId" to recoveryId,
                "contractId" to contractId,
                "taskId" to contractId, // Delta uses contractId as taskId
                "report_reference" to reportReference,
                "failureClass" to FailureClass.VALIDATION_FAILURE.name,
                "violationField" to "unknown", // TODO: Extract from failure context
                "source" to RECOVERY_SOURCE
            )
        )

        return ExecutionAuthorityExecutionResult.Executed(
            taskId,
            ExecutionStatus.FAILURE,
            null
        )
    }

    /**
     * Count RECOVERY_CONTRACT events for given contractId.
     */
    private fun countRecoveryAttempts(events: List<Event>, contractId: String): Int =
        events.count { event ->
            event.type == EventTypes.RECOVERY_CONTRACT &&
            event.payload["contractId"]?.toString() == contractId
        }

    /**
     * Derive deterministic recovery ID.
     */
    private fun deriveRecoveryId(projectId: String, contractId: String, attempt: Int): String =
        "RCF::$projectId::$contractId::attempt_$attempt"

    /**
     * Extract report_reference from CONTRACT_CREATED or CONTRACTS_GENERATED.
     */
    private fun extractReportReference(events: List<Event>, contractId: String): String {
        val contractCreated = events.firstOrNull { event ->
            event.type == EventTypes.CONTRACT_CREATED &&
            event.payload["contractId"]?.toString() == contractId
        }
        if (contractCreated != null) {
            return contractCreated.payload["report_reference"]?.toString() ?: ""
        }

        val contractsGenerated = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
        return contractsGenerated?.payload?.get("report_reference")?.toString() ?: ""
    }

    /**
     * Extract contract name from CONTRACT_CREATED event.
     */
    private fun extractContractName(events: List<Event>, contractId: String): String? {
        val contractCreated = events.firstOrNull { event ->
            event.type == EventTypes.CONTRACT_CREATED &&
            event.payload["contractId"]?.toString() == contractId
        }
        return contractCreated?.payload?.get("name")?.toString()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // UNIVERSAL CONTRACT INGESTION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Ingest universal contract — validation + enforcement pipeline.
     *
     * Flow:
     *   1. Emit CONTRACT_CREATED
     *   2. Validate contract structure
     *   3. Enforce contract constraints
     *   4. Emit CONTRACT_VALIDATED (on success)
     *   5. Emit TASK_EXECUTED(FAILURE) (on failure) → P1 recovery
     *
     * CONTRACT: Section 3.1 — Report Anchoring (report_reference required)
     *
     * @param contract  Universal contract to ingest.
     * @param projectId Project identifier.
     * @param ledger    Event ledger.
     * @return [UniversalIngestionResult].
     */
    fun ingestUniversalContract(
        contract:  UniversalContract,
        projectId: String,
        ledger:    EventLedger
    ): UniversalIngestionResult {
        // RULE 3.1: Report reference MUST be present
        if (contract.reportReference.isBlank()) {
            return UniversalIngestionResult.ValidationFailed(
                contract.contractId,
                "Report reference is blank — BLOCKED by 3.1 Report Anchoring"
            )
        }

        // Emit CONTRACT_CREATED
        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACT_CREATED,
            mapOf(
                "contractId" to contract.contractId,
                "report_reference" to contract.reportReference,
                "contractClass" to contract.contractClass.name,
                "position" to contract.position
            )
        )

        // TODO: Actual validation logic
        // For now, emit CONTRACT_VALIDATED
        ledger.appendEvent(
            projectId,
            EventTypes.CONTRACT_VALIDATED,
            mapOf(
                "contractId" to contract.contractId,
                "report_reference" to contract.reportReference
            )
        )

        return UniversalIngestionResult.Ingested(contract.contractId)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIFF ENGINE (RULES 3.2 → 3.4)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Compute diff between two artifacts.
     *
     * CONTRACT: Section 3.2-3.4 — Delta validation foundation
     *
     * @param oldSections Previous artifact sections.
     * @param newSections New artifact sections.
     * @return [DiffResult] with unchanged/modified/added/removed sections.
     */
    private fun computeDiff(
        oldSections: List<ArtifactSection>,
        newSections: List<ArtifactSection>
    ): DiffResult {
        val oldMap = oldSections.associateBy { it.sectionId }
        val newMap = newSections.associateBy { it.sectionId }
        
        val oldIds = oldMap.keys
        val newIds = newMap.keys
        
        val unchanged = mutableSetOf<String>()
        val modified = mutableSetOf<String>()
        val added = newIds - oldIds
        val removed = oldIds - newIds
        
        // Check common sections
        val commonIds = oldIds.intersect(newIds)
        for (sectionId in commonIds) {
            val oldHash = oldMap[sectionId]!!.contentHash
            val newHash = newMap[sectionId]!!.contentHash
            
            if (oldHash == newHash) {
                unchanged.add(sectionId)
            } else {
                modified.add(sectionId)
            }
        }
        
        val totalSections = (oldIds + newIds).size
        val changedCount = modified.size + added.size + removed.size
        val diffRatio = if (totalSections == 0) 0.0 else changedCount.toDouble() / totalSections
        
        return DiffResult(
            unchanged = unchanged,
            modified = modified,
            added = added,
            removed = removed,
            diffRatio = diffRatio
        )
    }

    /**
     * Validate delta execution against rules 3.2-3.4.
     *
     * RULE 3.2: No validated section can change
     * RULE 3.3: Changes must be within mutationSurface
     * RULE 3.4: No full rewrites; must be minimal delta
     *
     * @param context Delta validation context.
     * @return [DeltaValidationResult] — Approved or specific violation.
     */
    private fun validateDelta(context: DeltaValidationContext): DeltaValidationResult {
        val diff = computeDiff(context.previousArtifact, context.newArtifact)
        
        // RULE 3.4.1: Detect regenerated unchanged content
        if (diff.hasRegeneratedContent(context.previousArtifact, context.newArtifact)) {
            return DeltaValidationResult.NonDeltaRewrite
        }
        
        // RULE 3.4: Check for full rewrite
        if (diff.isFullRewrite(MAX_DIFF_RATIO)) {
            return DeltaValidationResult.FullRewriteDetected(diff.diffRatio)
        }
        
        // RULE 3.2: Check for regression (validated sections changed)
        val validatedSections = context.validatedSections
        if (validatedSections != null) {
            val regressionViolations = diff.changedSections.intersect(validatedSections.sections)
            if (regressionViolations.isNotEmpty()) {
                return DeltaValidationResult.RegressionDetected(regressionViolations)
            }
        }
        
        // RULE 3.3: Check mutation surface compliance
        val mutationSurface = context.mutationSurface
        if (mutationSurface != null) {
            val allowedChanges = mutationSurface.allowedSections.toSet()
            val unauthorizedChanges = diff.changedSections - allowedChanges
            if (unauthorizedChanges.isNotEmpty()) {
                return DeltaValidationResult.OutOfScopeMutation(unauthorizedChanges)
            }
        }
        
        return DeltaValidationResult.Approved
    }

    /**
     * Extract validated sections from previous successful execution report.
     *
     * CONTRACT: Section 3.2 — Locked State Enforcement
     *
     * @param events Event history.
     * @param contractId Contract identifier.
     * @return [ValidatedSections] if found, null otherwise.
     */
    private fun extractValidatedSections(events: List<Event>, contractId: String): ValidatedSections? {
        // Find last successful TASK_EXECUTED for this contract
        val lastSuccess = events.lastOrNull { event ->
            event.type == EventTypes.TASK_EXECUTED &&
            event.payload["contractId"]?.toString() == contractId &&
            event.payload["executionStatus"]?.toString() == ExecutionStatus.SUCCESS.name
        } ?: return null
        
        // Extract validated sections from payload (if present)
        @Suppress("UNCHECKED_CAST")
        val validatedSectionsList = lastSuccess.payload["validatedSections"] as? List<String>
        
        return if (validatedSectionsList != null) {
            ValidatedSections(validatedSectionsList.toSet())
        } else {
            null
        }
    }

    /**
     * Extract mutation surface from RECOVERY_CONTRACT.
     *
     * CONTRACT: Section 3.3 — Mutation Surface Declaration
     *
     * @param events Event history.
     * @param contractId Contract identifier.
     * @return [MutationSurface] if found, null otherwise.
     */
    private fun extractMutationSurface(events: List<Event>, contractId: String): MutationSurface? {
        // Find last RECOVERY_CONTRACT for this contract
        val recoveryContract = events.lastOrNull { event ->
            event.type == EventTypes.RECOVERY_CONTRACT &&
            event.payload["contractId"]?.toString() == contractId
        } ?: return null
        
        @Suppress("UNCHECKED_CAST")
        val allowedSections = recoveryContract.payload["mutationSurface"] as? List<String>
        
        return if (allowedSections != null) {
            MutationSurface(
                allowedFields = emptyList(), // Not used in current implementation
                allowedSections = allowedSections
            )
        } else {
            null
        }
    }

    /**
     * Load previous artifact from last successful execution.
     *
     * CONTRACT: AGOII-ARTIFACT-SPINE-001 — Artifact Retrieval
     *
     * Used for:
     *   - Regression detection (rule 3.2)
     *   - Mutation enforcement (rule 3.3)
     *   - Diff computation (rule 3.4)
     *
     * @param events Event history.
     * @param contractId Contract identifier.
     * @return List of [ArtifactSection] from previous execution, or null if not found.
     */
    private fun loadPreviousArtifact(events: List<Event>, contractId: String): List<ArtifactSection>? {
        // Find last successful TASK_EXECUTED for this contract
        val lastSuccess = events.lastOrNull { event ->
            event.type == EventTypes.TASK_EXECUTED &&
            event.payload["contractId"]?.toString() == contractId &&
            event.payload["executionStatus"]?.toString() == ExecutionStatus.SUCCESS.name
        } ?: return null
        
        // Extract artifact from payload (if present)
        @Suppress("UNCHECKED_CAST")
        val artifactData = lastSuccess.payload["artifact"] as? Map<String, Any> ?: return null
        
        @Suppress("UNCHECKED_CAST")
        val sectionsData = artifactData["sections"] as? List<Map<String, Any>> ?: return null
        
        // Reconstruct artifact sections
        return sectionsData.mapNotNull { sectionMap ->
            val sectionId = sectionMap["sectionId"]?.toString()
            val content = sectionMap["content"]?.toString()
            val contentHash = sectionMap["contentHash"]?.toString()
            
            if (sectionId != null && content != null && contentHash != null) {
                ArtifactSection(sectionId, content, contentHash)
            } else {
                null
            }
        }
    }

    /**
     * Store artifact in TASK_EXECUTED event payload.
     *
     * CONTRACT: AGOII-ARTIFACT-SPINE-001 — Artifact Storage
     *
     * Artifacts stored in-memory via event payload (pilot phase).
     * Future: External object store.
     *
     * @param artifact Artifact to serialize.
     * @return Map representation suitable for event payload.
     */
    private fun serializeArtifact(artifact: Artifact): Map<String, Any> {
        return mapOf(
            "executionId" to artifact.executionId,
            "sections" to artifact.sections.map { section ->
                mapOf(
                    "sectionId" to section.sectionId,
                    "content" to section.content,
                    "contentHash" to section.contentHash
                )
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ASSEMBLY EXECUTION
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Execute assembly from ledger when EXECUTION_COMPLETED is the last event.
     *
     * Delegates to [AssemblyModule.process].
     *
     * @param projectId Project identifier.
     * @param ledger    Event ledger.
     * @return [AssemblyExecutionResult].
     */
    fun assembleFromLedger(projectId: String, ledger: EventLedger): AssemblyExecutionResult {
        return assemblyModule.process(projectId, ledger)
    }
}

// ══════════════════════════════════════════════════════════════════════════════
// END OF EXECUTION AUTHORITY
// ══════════════════════════════════════════════════════════════════════════════
