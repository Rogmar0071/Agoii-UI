package com.agoii.mobile.core

// ── AGOII-REPLAY-AUTHORITY-PARTITION-001 ─────────────────────────────────────
//
// ReplayStructuralState is partitioned into three authority-scoped views:
//
//   GovernanceView  — Governor ONLY.  Routing-safe, non-execution data.
//                     Governor MUST NOT access ExecutionView or AuditView.
//
//   ExecutionView   — ExecutionAuthority ONLY.  Execution lifecycle tracking
//                     (ICS, commit, per-task status).
//                     ExecutionAuthority MUST NOT access GovernanceView.
//
//   AuditView       — Read-only surface for UI, validators, and observability.
//                     No authority writes through this view.
//
// Authority isolation invariants:
//   - Governor reads ONLY governanceView.*
//   - ExecutionAuthority reads ONLY executionView.*
//   - UI / validators / observability read ONLY auditView.*
//   - Replay is the SOLE writer of all three views (fact reconstruction).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Authoritative state container produced by the Replay Engine.
 *
 * AGOII-REPLAY-AUTHORITY-PARTITION-001: access is partitioned by authority.
 * Each consuming module MUST only read from its designated view.
 */
data class ReplayStructuralState(
    val governanceView: GovernanceView,
    val executionView:  ExecutionView,
    val auditView:      AuditView
)

// ── GovernanceView ────────────────────────────────────────────────────────────

/**
 * GOVERNOR-ONLY VIEW.
 *
 * Contains routing-safe, non-execution data sufficient for the Governor state
 * machine to derive its next transition.  No execution semantics are exposed
 * here; Governor MUST NOT read [ExecutionView] or [AuditView].
 */
data class GovernanceView(
    /** Type of the most recent ledger event, or null when the ledger is empty. */
    val lastEventType: String?,

    /** Payload of the most recent ledger event.  Empty map when ledger is empty. */
    val lastEventPayload: Map<String, Any>,

    /**
     * Total contract count derived from the first CONTRACTS_GENERATED event.
     * Used by Governor for contract-issuance loop and CSL gate calculations.
     */
    val totalContracts: Int,

    /**
     * Report reference (RRID) from the first CONTRACTS_GENERATED event.
     * Propagated into TASK_ASSIGNED payloads (RRIL-1).
     */
    val reportReference: String,

    /**
     * RecoveryIds already present in DELTA_CONTRACT_CREATED events.
     * Used by Governor for CLC-1 idempotency checks.
     */
    val deltaContractRecoveryIds: Set<String>,

    /**
     * TaskIds already present in TASK_ASSIGNED events.
     * Used by Governor for delta-loop TASK_ASSIGNED idempotency.
     */
    val taskAssignedTaskIds: Set<String>,

    /**
     * contract_id from the most recent CONTRACT_STARTED event.
     * Used by Governor when constructing CONTRACT_COMPLETED payloads.
     */
    val lastContractStartedId: String,

    /**
     * position from the most recent CONTRACT_STARTED event.
     * Used by Governor as a fallback when TASK_ASSIGNED.position is absent.
     */
    val lastContractStartedPosition: Int?
)

// ── ExecutionView ─────────────────────────────────────────────────────────────

/**
 * EXECUTION-AUTHORITY-ONLY VIEW.
 *
 * Tracks execution lifecycle: per-task status, ICS phase, and commit phase.
 * Governor MUST NOT read this view.
 */
data class ExecutionView(
    /**
     * Per-task execution status map (taskId → status string).
     *
     * Possible values (lifecycle order):
     *   "ASSIGNED", "STARTED", "EXECUTED_SUCCESS", "EXECUTED_FAILURE",
     *   "COMPLETED", "FAILED", "VALIDATED"
     *
     * Last write wins across retries / reassignments for the same taskId.
     */
    val taskStatus: Map<String, String>,

    /** True when ICS_STARTED has been seen in the ledger. */
    val icsStarted: Boolean,

    /** True when ICS_COMPLETED has been seen in the ledger. */
    val icsCompleted: Boolean,

    /** True when COMMIT_CONTRACT has been seen in the ledger. */
    val commitContractExists: Boolean,

    /** True when COMMIT_EXECUTED has been seen (commit approved). */
    val commitExecuted: Boolean,

    /** True when COMMIT_ABORTED has been seen (commit rejected). */
    val commitAborted: Boolean,

    /** Derived: commitContractExists && !commitExecuted && !commitAborted. */
    val commitPending: Boolean
)

// ── AuditView ─────────────────────────────────────────────────────────────────

/**
 * READ-ONLY AUDIT SURFACE.
 *
 * Consumed by UI, validators, and observability modules.
 * No authority writes through this view.
 */
data class AuditView(
    /** Intent phase structural state. */
    val intent: IntentStructuralState,

    /** Contract generation structural state. */
    val contracts: ContractStructuralState,

    /** Task execution structural state. */
    val execution: ExecutionStructuralState,

    /** Assembly phase structural state. */
    val assembly: AssemblyStructuralState,

    /**
     * Canonical execution validity flag.
     * True when count(TASK_EXECUTED SUCCESS) == totalContracts (FS-2).
     */
    val executionValid: Boolean,

    /**
     * Canonical assembly validity flag.
     * True when assemblyStarted && assemblyCompleted && executionValid.
     */
    val assemblyValid: Boolean,

    /**
     * Canonical ICS validity flag.
     * True when icsStarted && icsCompleted && assemblyValid.
     */
    val icsValid: Boolean,

    /**
     * Canonical commit validity flag (V3).
     * True when commitContractExists && (commitExecuted || commitAborted).
     */
    val commitValid: Boolean
)

// ── Nested structural sub-states (unchanged) ─────────────────────────────────

data class IntentStructuralState(
    val structurallyComplete: Boolean
)

data class ContractStructuralState(
    val generated: Boolean,
    val valid: Boolean,
    val totalContracts: Int = 0
)

data class ExecutionStructuralState(
    val totalTasks: Int,
    val assignedTasks: Int,
    val completedTasks: Int,
    val validatedTasks: Int,
    val fullyExecuted: Boolean,
    val successfulTasks: Int = 0
)

data class AssemblyStructuralState(
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean,
    /** Legacy field — uses fullyExecuted gate for backward compat with old tests/consumers. */
    val assemblyValid: Boolean
)

// ── Replay Engine ─────────────────────────────────────────────────────────────

class Replay(private val eventStore: EventRepository) {

    fun replayStructuralState(projectId: String): ReplayStructuralState {
        val events = eventStore.loadEvents(projectId)
        return deriveStructuralState(events)
    }

    fun deriveStructuralState(events: List<Event>): ReplayStructuralState {
        // ── Governance accumulators ───────────────────────────────────────────
        var totalContractsFromLedger = 0
        var reportReference = ""
        val deltaContractRecoveryIds = mutableSetOf<String>()
        val taskAssignedTaskIds = mutableSetOf<String>()
        var lastContractStartedId = ""
        var lastContractStartedPosition: Int? = null

        // ── Execution accumulators ────────────────────────────────────────────
        val taskStatusMutable = mutableMapOf<String, String>()
        var icsStarted = false
        var icsCompleted = false
        var commitContractExists = false
        var commitExecuted = false
        var commitAborted = false

        // ── Audit accumulators ────────────────────────────────────────────────
        var intentSubmitted = false
        var contractsGenerated = false
        var assemblyStarted = false
        var assemblyValidated = false
        var assemblyCompleted = false
        var assignedTasks = 0
        var completedTasks = 0
        var validatedTasks = 0
        // TASK_EXECUTED(SUCCESS) count for executionValid (FS-2)
        var successfulTaskExecutions = 0

        for (event in events) {
            when (event.type) {
                // ── Intent / contract ─────────────────────────────────────────
                EventTypes.INTENT_SUBMITTED    -> intentSubmitted = true

                EventTypes.CONTRACTS_GENERATED -> {
                    contractsGenerated = true
                    val contractsList = event.payload["contracts"] as? List<*>
                    totalContractsFromLedger = when {
                        !contractsList.isNullOrEmpty() -> contractsList.size
                        else -> resolveInt(event.payload["total"]) ?: 0
                    }
                    if (reportReference.isEmpty()) {
                        reportReference = event.payload["report_reference"]?.toString()
                            ?: event.payload["report_id"]?.toString()
                            ?: ""
                    }
                }

                EventTypes.CONTRACT_STARTED -> {
                    val cId = event.payload["contract_id"]?.toString() ?: ""
                    if (cId.isNotEmpty()) lastContractStartedId = cId
                    val pos = resolveInt(event.payload["position"])
                    if (pos != null) lastContractStartedPosition = pos
                }

                // ── Task lifecycle ────────────────────────────────────────────
                EventTypes.TASK_ASSIGNED -> {
                    assignedTasks++
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) {
                        taskStatusMutable[tid] = "ASSIGNED"
                        taskAssignedTaskIds.add(tid)
                    }
                }
                EventTypes.TASK_STARTED -> {
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "STARTED"
                }
                EventTypes.TASK_EXECUTED -> {
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    val execStatus = event.payload["executionStatus"]?.toString()
                    if (execStatus == "SUCCESS") {
                        successfulTaskExecutions++
                        if (tid.isNotEmpty()) taskStatusMutable[tid] = "EXECUTED_SUCCESS"
                    } else {
                        if (tid.isNotEmpty()) taskStatusMutable[tid] = "EXECUTED_FAILURE"
                    }
                }
                EventTypes.TASK_COMPLETED -> {
                    completedTasks++
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "COMPLETED"
                }
                EventTypes.TASK_FAILED -> {
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "FAILED"
                }
                EventTypes.TASK_VALIDATED -> {
                    validatedTasks++
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "VALIDATED"
                }

                // ── Recovery / delta ──────────────────────────────────────────
                EventTypes.DELTA_CONTRACT_CREATED -> {
                    val rid = event.payload["recoveryId"]?.toString() ?: ""
                    if (rid.isNotEmpty()) deltaContractRecoveryIds.add(rid)
                }

                // ── Assembly ──────────────────────────────────────────────────
                EventTypes.ASSEMBLY_STARTED   -> assemblyStarted   = true
                EventTypes.ASSEMBLY_VALIDATED -> assemblyValidated  = true
                EventTypes.ASSEMBLY_COMPLETED -> assemblyCompleted  = true

                // ── ICS ───────────────────────────────────────────────────────
                EventTypes.ICS_STARTED  -> icsStarted  = true
                EventTypes.ICS_COMPLETED -> icsCompleted = true

                // ── Commit ────────────────────────────────────────────────────
                EventTypes.COMMIT_CONTRACT -> commitContractExists = true
                EventTypes.COMMIT_EXECUTED -> commitExecuted       = true
                EventTypes.COMMIT_ABORTED  -> commitAborted        = true
            }
        }

        // ── Derived values ────────────────────────────────────────────────────

        val lastEvent = events.lastOrNull()
        val lastEventType    = lastEvent?.type
        val lastEventPayload: Map<String, Any> = lastEvent?.payload ?: emptyMap()

        val totalTasks = assignedTasks

        // Legacy fullyExecuted: validatedTasks gate (backward compat for pre-TASK_EXECUTED ledgers)
        val fullyExecuted = totalTasks > 0 && validatedTasks == totalTasks

        // Canonical executionValid: count(TASK_EXECUTED SUCCESS) == totalContracts (FS-2)
        val totalContracts = if (totalContractsFromLedger > 0) totalContractsFromLedger else totalTasks
        val executionValid  = totalContracts > 0 && successfulTaskExecutions == totalContracts

        // Legacy assemblyValid — fullyExecuted gate (backward compat with existing tests)
        val legacyAssemblyValid = assemblyStarted && assemblyCompleted && fullyExecuted

        // Canonical assemblyValid — executionValid gate
        val assemblyValidCanonical = assemblyStarted && assemblyCompleted && executionValid

        // icsValid = icsStarted && icsCompleted && canonical assemblyValid
        val icsValid = icsStarted && icsCompleted && assemblyValidCanonical

        // commitPending: COMMIT_CONTRACT seen but not yet resolved
        val commitPending = commitContractExists && !commitExecuted && !commitAborted

        // commitValid (V3): COMMIT_CONTRACT seen AND resolved
        val commitValid = commitContractExists && (commitExecuted || commitAborted)

        // ── Assemble views ────────────────────────────────────────────────────

        return ReplayStructuralState(
            governanceView = GovernanceView(
                lastEventType            = lastEventType,
                lastEventPayload         = lastEventPayload,
                totalContracts           = totalContracts,
                reportReference          = reportReference,
                deltaContractRecoveryIds = deltaContractRecoveryIds,
                taskAssignedTaskIds      = taskAssignedTaskIds,
                lastContractStartedId    = lastContractStartedId,
                lastContractStartedPosition = lastContractStartedPosition
            ),
            executionView = ExecutionView(
                taskStatus            = taskStatusMutable,
                icsStarted            = icsStarted,
                icsCompleted          = icsCompleted,
                commitContractExists  = commitContractExists,
                commitExecuted        = commitExecuted,
                commitAborted         = commitAborted,
                commitPending         = commitPending
            ),
            auditView = AuditView(
                intent = IntentStructuralState(
                    structurallyComplete = intentSubmitted
                ),
                contracts = ContractStructuralState(
                    generated      = contractsGenerated,
                    valid          = contractsGenerated,
                    totalContracts = totalContracts
                ),
                execution = ExecutionStructuralState(
                    totalTasks      = totalTasks,
                    assignedTasks   = assignedTasks,
                    completedTasks  = completedTasks,
                    validatedTasks  = validatedTasks,
                    fullyExecuted   = fullyExecuted,
                    successfulTasks = successfulTaskExecutions
                ),
                assembly = AssemblyStructuralState(
                    assemblyStarted   = assemblyStarted,
                    assemblyValidated = assemblyValidated,
                    assemblyCompleted = assemblyCompleted,
                    // Legacy assemblyValid uses fullyExecuted gate for backward compat.
                    // Canonical truth-layer assemblyValid is AuditView.assemblyValid below.
                    assemblyValid     = legacyAssemblyValid
                ),
                executionValid = executionValid,
                assemblyValid  = assemblyValidCanonical,
                icsValid       = icsValid,
                commitValid    = commitValid
            )
        )
    }

    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
