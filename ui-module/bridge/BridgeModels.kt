package agoii.ui.bridge

/**
 * Sealed data models for the UI module bridge boundary.
 *
 * These types mirror the core system's structural state models and are the ONLY
 * types that cross the bridge. The core system populates these via [CoreBridge];
 * the UI module consumes them read-only.
 *
 * NO core imports. NO system dependencies. These are UI-owned types.
 */

/**
 * Immutable ledger event as seen by the UI module.
 *
 * @property type           Event type string (e.g. "intent_submitted", "task_started").
 * @property payload        Arbitrary key-value metadata attached to the event.
 * @property id             Unique event identifier (UUID).
 * @property sequenceNumber Monotonic position in the ledger.
 * @property timestamp      Milliseconds since epoch.
 */
data class UIEvent(
    val type: String,
    val payload: Map<String, Any> = emptyMap(),
    val id: String = "",
    val sequenceNumber: Long = -1L,
    val timestamp: Long = 0L
)

/**
 * Authority-partitioned structural state as replayed from the ledger.
 * The UI module reads ONLY [auditView] for state projection.
 */
data class UIReplayState(
    val governanceView: UIGovernanceView,
    val executionView: UIExecutionView,
    val auditView: UIAuditView
)

// ── Governance View ─────────────────────────────────────────────────────────

data class UIGovernanceView(
    val lastEventType: String?,
    val lastEventPayload: Map<String, Any>,
    val totalContracts: Int,
    val reportReference: String,
    val deltaContractRecoveryIds: Set<String>,
    val taskAssignedTaskIds: Set<String>,
    val lastContractStartedId: String,
    val lastContractStartedPosition: Int?
)

// ── Execution View ──────────────────────────────────────────────────────────

data class UIExecutionView(
    val taskStatus: Map<String, String>,
    val icsStarted: Boolean,
    val icsCompleted: Boolean,
    val commitContractExists: Boolean,
    val commitExecuted: Boolean,
    val commitAborted: Boolean,
    val executionStatus: String,
    val showCommitPanel: Boolean
)

// ── Audit View (UI-facing, read-only) ───────────────────────────────────────

data class UIAuditView(
    val intent: UIIntentState,
    val contracts: UIContractState,
    val execution: UIExecutionState,
    val assembly: UIAssemblyState
)

data class UIIntentState(
    val structurallyComplete: Boolean
)

data class UIContractState(
    val generated: Boolean,
    val valid: Boolean,
    val totalContracts: Int = 0
)

data class UIExecutionState(
    val totalTasks: Int,
    val assignedTasks: Int,
    val completedTasks: Int,
    val validatedTasks: Int,
    val successfulTasks: Int = 0
)

data class UIAssemblyState(
    val assemblyStarted: Boolean,
    val assemblyValidated: Boolean,
    val assemblyCompleted: Boolean
)

/**
 * Result of a user interaction processed through the bridge.
 *
 * @property content  The textual result of the interaction.
 * @property success  Whether the interaction completed without error.
 */
data class UIInteractionResult(
    val content: String,
    val success: Boolean = true
)
