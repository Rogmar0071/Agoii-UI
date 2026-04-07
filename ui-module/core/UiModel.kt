package agoii.ui.core

/**
 * UI-01 REPLAY BINDING — Data models mapped from ReplayStructuralState.
 *
 * Every field displayed in UI MUST exist in these models.
 * Every value in these models MUST originate from ReplayStructuralState.
 * ZERO derived fields. ZERO computed properties.
 *
 * Source of truth: system/replay/ReplayBuilder.js → buildReplayStructuralState()
 *
 * Invariants:
 *   RL-01  (REPLAY_PURITY)    — All values from replay only
 *   R2     (UI_AUTHORITY_LOCK) — UI reads ONLY from these views
 *   UI-VAL-04                  — All displayed values exist in Replay
 */

/**
 * GovernanceView — governance layer state from replay.
 *
 * Currently extensible for future MQP phases.
 * Fields will be populated as governance events are defined.
 */
data class GovernanceView(
    val lastEventType: String = "",
    val lastEventPayload: String = "",
    val reportReference: String = "",
    val hasLastEvent: Boolean = false
)

/**
 * ExecutionView — execution layer state from replay.
 *
 * Maps directly to ReplayBuilder.deriveResilienceState() output:
 *   circuitState     → 'closed' | 'open' | 'half_open'
 *   circuitFailures  → cumulative failure count
 *   probeStatus      → 'none' | 'awaiting_probe' | 'assigned'
 *   probeOwner       → probe execution ID or null
 *   lastTransitionAt → timestamp of last state transition or null
 *
 * Additional UI-ready fields:
 *   executionStatus      → human-readable status string
 *   showCommitPanel      → pre-computed visibility flag from replay
 *   lastContractStartedId → most recent contract in progress
 */
data class ExecutionView(
    val circuitState: String = "closed",
    val circuitFailures: Int = 0,
    val probeStatus: String = "none",
    val probeOwner: String? = null,
    val lastTransitionAt: Long? = null,
    val executionStatus: String = "",
    val showCommitPanel: Boolean = false,
    val lastContractStartedId: String = ""
)

/**
 * AuditView — audit layer state from replay.
 *
 * Maps directly to ReplayBuilder.buildReplayStructuralState().auditView:
 *   totalEvents  → event count in ledger
 *   contractIds  → list of known contract identifiers
 */
data class AuditView(
    val totalEvents: Int = 0,
    val contractIds: List<String> = emptyList(),
    val hasContracts: Boolean = false,
    val lastEventType: String? = null,
    val lastEventPayload: String? = null,
    val executionStatus: String = "idle",
    val finalOutput: String? = null
)

/**
 * ConversationMessage — a single message turn projected from Replay state.
 *
 * Maps 1:1 from core ConversationMessage (derived from USER_MESSAGE_SUBMITTED /
 * SYSTEM_MESSAGE_EMITTED events). UI MUST NOT construct or alter these.
 *
 * Invariants:
 *   RL-01 (REPLAY_PURITY)  — originate exclusively from Replay
 *   MQP-PHASE-3            — 1 event = 1 message, no merging or inference
 */
data class ConversationMessage(
    val id: String,
    val text: String,
    val isUser: Boolean
)

/**
 * IntentConstructionView — intent construction loop state projected from Replay.
 *
 * Maps 1:1 from core IntentConstructionView
 * (MQP-INTENT-CONSTRUCTION-LOOP-v1).
 *
 * NOTE: The `constraints` field from the core view is intentionally omitted here.
 * Constraints are a raw Map<String, Any> consumed by Nemoclaw, not by the UI layer.
 * Exposing a raw map to UI would violate ARCH-03 (UI must not access Nemoclaw internals)
 * and ARCH-06 (if UI renders constraints it must be pre-formatted by Replay, not a map).
 * When constraint display is required, a pre-formatted `constraintsSummary: String`
 * field must be derived in the core Replay layer and surfaced here.
 *
 * Invariants:
 *   RL-01 (REPLAY_PURITY)  — all values originate exclusively from Replay
 *   ARCH-06 (MODEL_COMPLETENESS_RULE) — UI never derives intent construction state
 */
data class IntentConstructionView(
    val intentId: String? = null,
    val objective: String? = null,
    /** Forward-only status: "none"|"partial"|"in_progress"|"completed"|"approval_requested"|"approved"|"rejected" */
    val status: String = "none",
    val approvalRequired: Boolean = false,
    val completeness: Double = 0.0
)

/**
 * ReplayStructuralState — the COMPLETE state surface for UI rendering.
 *
 * This is the ONLY object the UI reads.
 * Matches system/replay/ReplayBuilder.js → buildReplayStructuralState() output.
 */
data class ReplayStructuralState(
    val governanceView: GovernanceView = GovernanceView(),
    val executionView: ExecutionView = ExecutionView(),
    val auditView: AuditView = AuditView(),
    val conversation: List<ConversationMessage> = emptyList(),
    val intentConstruction: IntentConstructionView = IntentConstructionView()
)

/**
 * UiModel — view-model wrapper consumed by screen composables.
 *
 * Maps 1:1 from ReplayStructuralState views.
 * NO additional computation. NO derived fields.
 */
data class UiModel(
    val governance: GovernanceView,
    val execution: ExecutionView,
    val audit: AuditView,
    val chat: ChatUiModel,
    val intentConstruction: IntentConstructionView = IntentConstructionView()
)

/**
 * ProjectDescriptor — metadata for multi-project support (UI-04).
 */
data class ProjectDescriptor(
    val id: String,
    val name: String,
    val description: String = ""
)
