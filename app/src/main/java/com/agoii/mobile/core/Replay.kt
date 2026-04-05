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

    /**
     * Fully-resolved execution status for UI consumption.
     *
     * CONTRACT: MQP-EXECUTIONVIEW-COMPLETION-001
     *
     * Values (ENUM):
     *   "not_started" - No task has been started
     *   "running"     - Task started but no execution result yet
     *   "success"     - Last TASK_EXECUTED.executionStatus == "SUCCESS"
     *   "failed"      - Last TASK_EXECUTED.executionStatus == "FAILURE"
     *
     * Derived during replay construction. UI MUST NOT apply logic to determine this.
     */
    val executionStatus: String,

    /**
     * Fully-resolved commit panel visibility flag for UI consumption.
     *
     * CONTRACT: MQP-EXECUTIONVIEW-COMPLETION-001
     *
     * True when:
     *   commitContractExists AND NOT commitExecuted AND NOT commitAborted
     *
     * Derived during replay construction. UI MUST NOT apply logic to determine this.
     */
    val showCommitPanel: Boolean
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
     * Ordered list of contract_ids from CONTRACT_STARTED events.
     * Derived during replay construction. UI MUST NOT derive this independently.
     */
    val contractIds: List<String> = emptyList(),

    /**
     * True when at least one CONTRACT_STARTED event exists in the ledger.
     * Derived during replay construction. UI MUST NOT apply logic to determine this.
     */
    val hasContracts: Boolean = false
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
    val successfulTasks: Int = 0
)

data class AssemblyStructuralState(
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean
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
        var taskStartedSeen = false
        var lastTaskExecutedStatus: String? = null

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
        val contractIdsMutable = mutableListOf<String>()

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
                    if (cId.isNotEmpty()) {
                        lastContractStartedId = cId
                        contractIdsMutable.add(cId)
                    }
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
                    taskStartedSeen = true
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    if (tid.isNotEmpty()) taskStatusMutable[tid] = "STARTED"
                }
                EventTypes.TASK_EXECUTED -> {
                    val tid = event.payload["taskId"]?.toString() ?: ""
                    val execStatus = event.payload["executionStatus"]?.toString()
                    lastTaskExecutedStatus = execStatus
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
        val totalContracts = if (totalContractsFromLedger > 0) totalContractsFromLedger else totalTasks

        // ── Compute executionStatus (MQP-EXECUTIONVIEW-COMPLETION-001) ───────
        val executionStatus = when {
            !taskStartedSeen -> "not_started"
            lastTaskExecutedStatus == "SUCCESS" -> "success"
            lastTaskExecutedStatus == "FAILURE" -> "failed"
            else -> "running"
        }

        // ── Compute showCommitPanel (MQP-EXECUTIONVIEW-COMPLETION-001) ───────
        val showCommitPanel = commitContractExists && !commitExecuted && !commitAborted

        // ── Compute contractIds / hasContracts (MQP-FINAL-STABILIZATION-AND-MERGE-v1) ──
        val contractIds = contractIdsMutable.toList()
        val hasContracts = contractIds.isNotEmpty()

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
                executionStatus       = executionStatus,
                showCommitPanel       = showCommitPanel
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
                    successfulTasks = successfulTaskExecutions
                ),
                assembly = AssemblyStructuralState(
                    assemblyStarted   = assemblyStarted,
                    assemblyValidated = assemblyValidated,
                    assemblyCompleted = assemblyCompleted
                ),
                contractIds  = contractIds,
                hasContracts = hasContracts
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
