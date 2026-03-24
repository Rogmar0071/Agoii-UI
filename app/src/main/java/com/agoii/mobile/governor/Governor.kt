package com.agoii.mobile.governor

import com.agoii.mobile.assembly.AssemblyValidator
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.Replay
import com.agoii.mobile.decision.DecisionAction
import com.agoii.mobile.decision.DecisionEngine
import com.agoii.mobile.execution.ExecutionOrchestrator
import com.agoii.mobile.execution.ValidationVerdict
import com.agoii.mobile.governance.AssemblyModuleAdapter
import com.agoii.mobile.governance.ContractDescriptor
import com.agoii.mobile.governance.ContractIssuanceAdapter
import com.agoii.mobile.governance.ContractorModuleAdapter
import com.agoii.mobile.governance.ContractSurfaceLayer
import com.agoii.mobile.governance.GovernanceGate
import com.agoii.mobile.governance.StateSurfaceMirror
import com.agoii.mobile.governance.SurfaceType
import com.agoii.mobile.tasks.Task
import com.agoii.mobile.tasks.TaskAssignmentStatus

/**
 * Governor — the ONLY execution authority in the system.
 *
 * Rules:
 *  - Reads the current ledger state.
 *  - Determines the next valid event using VALID_TRANSITIONS.
 *  - Appends EXACTLY ONE event per invocation (or zero if waiting/completed).
 *  - Never mutates state directly.
 *  - Stops and returns WAITING_FOR_APPROVAL when the last event is contracts_ready.
 *  - Contract issuance is gated by SSM state and CSL validation; returns DRIFT on failure.
 *
 * Full execution lifecycle (one governor call per arrow):
 *   execution_started
 *     → contract_started  (position=1, total=N)
 *     → task_assigned
 *     → task_started
 *     → task_completed
 *     → task_validated
 *     → contract_completed (position=1, total=N)
 *     → contract_started  (position=2, total=N)
 *     → …
 *     → contract_completed (position=N, total=N)
 *     → execution_completed
 *     → assembly_started
 *     → assembly_validated
 *     → assembly_completed
 */
class Governor(
    private val eventStore:   EventRepository,
    private val registry:     ContractorRegistry    = ContractorRegistry(),
    private val orchestrator: ExecutionOrchestrator = ExecutionOrchestrator()
) {

    private val ssm  = StateSurfaceMirror()
    private val csl  = ContractSurfaceLayer()
    private val gate = GovernanceGate(eventStore)
    // DecisionEngine is the sole authority for task-failure branching decisions.
    private val decisionEngine = DecisionEngine(registry)

    init {
        ssm.initialize()
        ssm.activateSurface(SurfaceType.LG)
    }

    companion object {
        /**
         * Fixed validation capacity — deterministic baseline for CSL evaluation.
         * Must not be derived dynamically or set to total * N.
         */
        const val VC = 5

        /**
         * Defines every legal automatic single-step transition driven by the governor.
         * User-driven transitions (intent_submitted, contracts_approved) are NOT listed here
         * because they are triggered by UI actions, not the governor.
         *
         * Contract lifecycle steps (contract_started ↔ contract_completed) repeat N times
         * and depend on ledger-derived state; they are handled in dedicated branches below.
         * The full assembly pipeline is driven entirely by this map after all contracts complete.
         */
        val VALID_TRANSITIONS: Map<String, String> = mapOf(
            EventTypes.INTENT_SUBMITTED    to EventTypes.CONTRACTS_GENERATED,
            EventTypes.CONTRACTS_GENERATED to EventTypes.CONTRACTS_READY,
            EventTypes.CONTRACTS_APPROVED  to EventTypes.EXECUTION_STARTED,
            EventTypes.EXECUTION_COMPLETED to EventTypes.ASSEMBLY_STARTED,
            EventTypes.ASSEMBLY_VALIDATED  to EventTypes.ASSEMBLY_COMPLETED
        )
    }

    enum class GovernorResult {
        /** Governor appended one event and advanced the ledger. */
        ADVANCED,
        /** Governor is paused; waiting for explicit user approval. */
        WAITING_FOR_APPROVAL,
        /** Governor is paused; no verified contractor is available for the active task. */
        WAITING,
        /** Execution is fully complete (assembly_completed reached). */
        COMPLETED,
        /** Ledger is empty or in an unknown terminal state. */
        NO_EVENT,
        /** Contract issuance blocked by SSM or CSL gate. */
        DRIFT
    }

    fun runGovernor(projectId: String): GovernorResult {
        val events = eventStore.loadEvents(projectId)
        if (events.isEmpty()) return GovernorResult.NO_EVENT

        val replayState = Replay(eventStore).replay(projectId)

        val lastEvent = events.last()
        val lastType  = lastEvent.type

        if (!isStateResolvable(events, lastEvent)) {
            return GovernorResult.NO_EVENT
        }

        return when {
            // ── Approval gate — MUST stop; do not append anything ─────────────────
            lastType == EventTypes.CONTRACTS_READY    -> GovernorResult.WAITING_FOR_APPROVAL

            // ── Terminal state ────────────────────────────────────────────────────
            lastType == EventTypes.ASSEMBLY_COMPLETED -> GovernorResult.COMPLETED

            // ── execution_started → start the first contract ──────────────────────
            // Reads total from replay so the value is ledger-derived, not inferred.
            lastType == EventTypes.EXECUTION_STARTED -> {
                val issuanceAdapter = ContractIssuanceAdapter(
                    ssmInitialized  = ssm.isInitialized(),
                    lgSurfaceActive = ssm.getActiveSurfaces().contains(SurfaceType.LG),
                    cslResult       = csl.evaluate(ContractDescriptor(SurfaceType.LG, 1, 0, VC)),
                    position        = 1
                )
                val issuanceState = issuanceAdapter.getStateSignature()
                if (issuanceState["ssmInitialized"] != true ||
                    issuanceState["lgSurfaceActive"] != true ||
                    issuanceState["cslOutcome"] != "ALLOWED"
                ) return GovernorResult.DRIFT
                val total = Replay(eventStore).replay(projectId).totalContracts
                gate.appendEvent(
                    projectId, EventTypes.CONTRACT_STARTED,
                    mapOf(
                        "contract_id" to "contract_1",
                        "position"    to 1,
                        "total"       to total
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── contract_started → resolve task and assign contractor ─────────────
            // Step 1 of the task execution lifecycle.
            lastType == EventTypes.CONTRACT_STARTED -> {
                val contractId = lastEvent.payload["contract_id"] as? String ?: "unknown"
                val position   = resolveInt(lastEvent.payload["position"]) ?: 1
                val total      = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                val task       = taskForContract(contractId, position)
                val contractor = registry.findBestMatch(task.requiredCapabilities)
                val contractorAdapter = ContractorModuleAdapter(contractor)
                if (contractorAdapter.getStateSignature()["contractorAvailable"] != true) {
                    return GovernorResult.WAITING
                }
                gate.appendEvent(
                    projectId, EventTypes.TASK_ASSIGNED,
                    mapOf(
                        "taskId"       to task.taskId,
                        "contractorId" to contractor!!.id,
                        "contract_id"  to contractId,
                        "position"     to position,
                        "total"        to total
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── task_assigned → start the task ────────────────────────────────────
            // Step 2 of the task execution lifecycle.
            lastType == EventTypes.TASK_ASSIGNED -> {
                if (hasEventAfter(events, lastEvent, EventTypes.TASK_STARTED)) {
                    return GovernorResult.NO_EVENT
                }
                gate.appendEvent(
                    projectId, EventTypes.TASK_STARTED,
                    mapOf(
                        "taskId"       to (lastEvent.payload["taskId"]       ?: ""),
                        "contractorId" to (lastEvent.payload["contractorId"] ?: ""),
                        "contract_id"  to (lastEvent.payload["contract_id"]  ?: ""),
                        "position"     to (lastEvent.payload["position"]     ?: 0),
                        "total"        to (lastEvent.payload["total"]        ?: 0)
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── task_started → execute via contractor ─────────────────────────────
            // Step 3: Governor calls ExecutionOrchestrator (pure worker), reads the
            // execution result directly, and decides which event to emit.
            lastType == EventTypes.TASK_STARTED -> {
                val contractId    = lastEvent.payload["contract_id"]  as? String ?: "unknown"
                val contractorId  = lastEvent.payload["contractorId"] as? String ?: ""
                val taskId        = lastEvent.payload["taskId"]       as? String
                    ?: "$contractId-step1"
                val position      = resolveInt(lastEvent.payload["position"]) ?: 1
                val total         = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                val task          = taskForContract(contractId, position, taskId)
                val contractor    = registry.allVerified().find { it.id == contractorId }
                    ?: registry.findBestMatch(task.requiredCapabilities)
                    ?: return GovernorResult.WAITING
                val result = orchestrator.execute(task, contractor)
                // result is ONLY used to decide which event to emit
                if (hasEventAfter(events, lastEvent, EventTypes.TASK_COMPLETED) ||
                    hasEventAfter(events, lastEvent, EventTypes.TASK_FAILED)
                ) {
                    return GovernorResult.NO_EVENT
                }
                if (result.success) {
                    gate.appendEvent(
                        projectId, EventTypes.TASK_COMPLETED,
                        mapOf(
                            "taskId"       to task.taskId,
                            "contractorId" to contractor.id,
                            "contract_id"  to contractId,
                            "position"     to position,
                            "total"        to total
                        )
                    )
                } else {
                    gate.appendEvent(
                        projectId, EventTypes.TASK_FAILED,
                        mapOf(
                            "taskId"       to task.taskId,
                            "contractorId" to contractor.id,
                            "contract_id"  to contractId,
                            "position"     to position,
                            "total"        to total,
                            "reason"       to (result.error ?: "Execution error")
                        )
                    )
                }
                GovernorResult.ADVANCED
            }

            // ── task_completed → validate the result ──────────────────────────────
            // Step 4: Governor re-calls ExecutionOrchestrator (deterministic), reads the
            // validation verdict from the result directly, and decides which event to emit.
            lastType == EventTypes.TASK_COMPLETED -> {
                val contractId   = lastEvent.payload["contract_id"]  as? String ?: "unknown"
                val contractorId = lastEvent.payload["contractorId"] as? String ?: ""
                val taskId       = lastEvent.payload["taskId"]       as? String
                    ?: "$contractId-step1"
                val position     = resolveInt(lastEvent.payload["position"]) ?: 1
                val total        = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                val task         = taskForContract(contractId, position, taskId)
                val contractor   = registry.allVerified().find { it.id == contractorId }
                    ?: registry.findBestMatch(task.requiredCapabilities)
                    ?: return GovernorResult.WAITING
                val result = orchestrator.execute(task, contractor)
                // result is ONLY used to decide which event to emit
                if (hasEventAfter(events, lastEvent, EventTypes.TASK_VALIDATED)) {
                    return GovernorResult.NO_EVENT
                }
                if (result.validationResult.verdict == ValidationVerdict.VALIDATED) {
                    gate.appendEvent(
                        projectId, EventTypes.TASK_VALIDATED,
                        mapOf(
                            "taskId"      to task.taskId,
                            "contract_id" to contractId,
                            "position"    to position,
                            "total"       to total
                        )
                    )
                } else {
                    val reason = result.validationResult.failureReasons.joinToString("; ")
                    gate.appendEvent(
                        projectId, EventTypes.TASK_FAILED,
                        mapOf(
                            "taskId"       to task.taskId,
                            "contractorId" to contractor.id,
                            "contract_id"  to contractId,
                            "position"     to position,
                            "total"        to total,
                            "reason"       to reason
                        )
                    )
                }
                GovernorResult.ADVANCED
            }

            // ── task_validated → complete the contract ────────────────────────────
            // Step 5: Contract completes ONLY after task_validated (critical rule).
            lastType == EventTypes.TASK_VALIDATED -> {
                val contractId = lastEvent.payload["contract_id"] as? String ?: "unknown"
                val position   = resolveInt(lastEvent.payload["position"]) ?: 1
                val total      = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                if (hasEventAfter(events, lastEvent, EventTypes.CONTRACT_COMPLETED)) {
                    return GovernorResult.NO_EVENT
                }
                gate.appendEvent(
                    projectId, EventTypes.CONTRACT_COMPLETED,
                    mapOf(
                        "contract_id" to contractId,
                        "position"    to position,
                        "total"       to total
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── task_failed → retry / reassign / escalate ─────────────────────────
            // Governor owns the retry decision; DecisionEngine is the sole decision authority.
            lastType == EventTypes.TASK_FAILED -> {
                val contractId   = lastEvent.payload["contract_id"]  as? String ?: "unknown"
                val taskId       = lastEvent.payload["taskId"]       as? String
                    ?: "$contractId-step1"
                val contractorId = lastEvent.payload["contractorId"] as? String ?: ""
                val position     = resolveInt(lastEvent.payload["position"]) ?: 1
                val total        = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                val task         = taskForContract(contractId, position, taskId)
                val contractor   = registry.allVerified().find { it.id == contractorId }
                    ?: registry.findBestMatch(task.requiredCapabilities)
                    ?: return GovernorResult.NO_EVENT
                val failureCount = events.count {
                    it.type == EventTypes.TASK_FAILED && it.payload["taskId"] == taskId
                }
                val outcome = decisionEngine.evaluate(task, contractor, failureCount)
                when (outcome.action) {
                    DecisionAction.RETRY -> {
                        gate.appendEvent(
                            projectId, EventTypes.TASK_ASSIGNED,
                            mapOf(
                                "taskId"       to task.taskId,
                                "contractorId" to contractor.id,
                                "contract_id"  to contractId,
                                "position"     to position,
                                "total"        to total
                            )
                        )
                        GovernorResult.ADVANCED
                    }
                    DecisionAction.REASSIGN -> {
                        val newContractor = outcome.assignedContractor!!
                        gate.appendEvent(
                            projectId, EventTypes.CONTRACTOR_REASSIGNED,
                            mapOf(
                                "taskId"               to task.taskId,
                                "previousContractorId" to contractor.id,
                                "newContractorId"      to newContractor.id,
                                "contract_id"          to contractId,
                                "position"             to position,
                                "total"                to total
                            )
                        )
                        GovernorResult.ADVANCED
                    }
                    DecisionAction.ESCALATE -> {
                        gate.appendEvent(
                            projectId, EventTypes.CONTRACT_FAILED,
                            mapOf(
                                "taskId"      to task.taskId,
                                "contract_id" to contractId,
                                "reason"      to outcome.reason
                            )
                        )
                        GovernorResult.NO_EVENT
                    }
                }
            }

            // ── contractor_reassigned → re-assign task to new contractor ──────────
            lastType == EventTypes.CONTRACTOR_REASSIGNED -> {
                val taskId          = lastEvent.payload["taskId"]          as? String ?: ""
                val newContractorId = lastEvent.payload["newContractorId"] as? String ?: ""
                val contractId      = lastEvent.payload["contract_id"]     as? String ?: "unknown"
                val position        = resolveInt(lastEvent.payload["position"]) ?: 1
                val total           = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                gate.appendEvent(
                    projectId, EventTypes.TASK_ASSIGNED,
                    mapOf(
                        "taskId"       to taskId,
                        "contractorId" to newContractorId,
                        "contract_id"  to contractId,
                        "position"     to position,
                        "total"        to total
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── contract_completed → start next contract OR emit execution_completed
            // Reads position/total from the last event payload — fully explicit, no replay.
            lastType == EventTypes.CONTRACT_COMPLETED -> {
                val position = resolveInt(lastEvent.payload["position"]) ?: 1
                val total    = resolveInt(lastEvent.payload["total"])    ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
                if (position < total) {
                    if (replayState.executionCompleted) return GovernorResult.NO_EVENT
                    val nextPosition = position + 1
                    val issuanceAdapter = ContractIssuanceAdapter(
                        ssmInitialized  = ssm.isInitialized(),
                        lgSurfaceActive = ssm.getActiveSurfaces().contains(SurfaceType.LG),
                        cslResult       = csl.evaluate(ContractDescriptor(SurfaceType.LG, nextPosition, 0, VC)),
                        position        = nextPosition
                    )
                    val issuanceState = issuanceAdapter.getStateSignature()
                    if (issuanceState["ssmInitialized"] != true ||
                        issuanceState["lgSurfaceActive"] != true ||
                        issuanceState["cslOutcome"] != "ALLOWED"
                    ) return GovernorResult.DRIFT
                    gate.appendEvent(
                        projectId, EventTypes.CONTRACT_STARTED,
                        mapOf(
                            "contract_id" to "contract_$nextPosition",
                            "position"    to nextPosition,
                            "total"       to total
                        )
                    )
                } else {
                    if (replayState.executionCompleted) return GovernorResult.NO_EVENT
                    gate.appendEvent(
                        projectId, EventTypes.EXECUTION_COMPLETED,
                        mapOf("contracts_completed" to total)
                    )
                }
                GovernorResult.ADVANCED
            }

            // ── assembly_started → validate integrity before emitting assembly_validated ─
            // AssemblyValidator is a pure, state-driven check — no mutation occurs.
            // Governor reads assemblyValid from the assembly module state and decides.
            lastType == EventTypes.ASSEMBLY_STARTED -> {
                val assemblyResult  = AssemblyValidator().validate(replayState)
                val assemblyAdapter = AssemblyModuleAdapter(assemblyResult)
                if (assemblyAdapter.getStateSignature()["assemblyValid"] != true) {
                    return GovernorResult.NO_EVENT
                }
                if (replayState.assemblyValidated) return GovernorResult.NO_EVENT
                gate.appendEvent(projectId, EventTypes.ASSEMBLY_VALIDATED, emptyMap())
                GovernorResult.ADVANCED
            }

            // ── Standard single-step governor transition (covers assembly pipeline) ─
            VALID_TRANSITIONS.containsKey(lastType) -> {
                val nextType = VALID_TRANSITIONS[lastType]!!
                val payload  = buildPayload(nextType, lastEvent)
                gate.appendEvent(projectId, nextType, payload)
                GovernorResult.ADVANCED
            }

            else -> GovernorResult.NO_EVENT
        }
    }

    // ─── Resolution Guard — Prevents duplicate or premature transitions ───────────

    /**
     * Returns true if the Governor is allowed to process and advance from [lastEvent].
     * Returns false when the ledger already contains a successor event that means
     * the transition has already been performed (idempotency at entry).
     */
    private fun isStateResolvable(events: List<Event>, lastEvent: Event): Boolean {
        return when (lastEvent.type) {

            EventTypes.CONTRACTS_READY -> false

            EventTypes.TASK_ASSIGNED -> {
                !hasEventAfter(events, lastEvent, EventTypes.TASK_STARTED)
            }

            EventTypes.TASK_STARTED -> {
                !hasEventAfter(events, lastEvent, EventTypes.TASK_COMPLETED) &&
                !hasEventAfter(events, lastEvent, EventTypes.TASK_FAILED)
            }

            EventTypes.TASK_COMPLETED -> {
                !hasEventAfter(events, lastEvent, EventTypes.TASK_VALIDATED)
            }

            EventTypes.CONTRACT_COMPLETED -> {
                !hasEventAfter(events, lastEvent, EventTypes.CONTRACT_STARTED) &&
                !hasEventAfter(events, lastEvent, EventTypes.EXECUTION_COMPLETED)
            }

            else -> true
        }
    }

    /**
     * Returns true when any event of [eventType] appears after [afterEvent] in the ledger.
     * Uses structural equality (`.equals()`) to locate [afterEvent]'s position in the list.
     */
    private fun hasEventAfter(events: List<Event>, afterEvent: Event, eventType: String): Boolean {
        val idx = events.lastIndexOf(afterEvent)
        if (idx < 0) return false
        return events.drop(idx + 1).any { it.type == eventType }
    }

    private fun buildPayload(nextType: String, triggerEvent: Event): Map<String, Any> =
        when (nextType) {
            EventTypes.CONTRACTS_GENERATED -> {
                val intent = triggerEvent.payload["objective"] as? String ?: "unknown"
                mapOf(
                    "total"         to EventTypes.DEFAULT_TOTAL_CONTRACTS,
                    "source_intent" to intent,
                    "contracts"     to listOf(
                        mapOf("id" to "contract_1", "name" to "Core Setup"),
                        mapOf("id" to "contract_2", "name" to "Integration"),
                        mapOf("id" to "contract_3", "name" to "Validation")
                    )
                )
            }
            EventTypes.CONTRACTS_READY   -> mapOf("total_contracts" to EventTypes.DEFAULT_TOTAL_CONTRACTS)
            EventTypes.EXECUTION_STARTED -> mapOf("total_contracts" to EventTypes.DEFAULT_TOTAL_CONTRACTS)
            else                         -> emptyMap()
        }

    /**
     * Derive a minimal [Task] from contract identity information stored in the ledger.
     * Uses an empty capability requirement so any verified contractor qualifies.
     */
    private fun taskForContract(
        contractId: String,
        position:   Int,
        taskId:     String = "$contractId-step1"
    ): Task = Task(
        taskId               = taskId,
        contractReference    = contractId,
        stepReference        = 1,
        module               = "CORE",
        description          = "Execute $contractId at position $position",
        requiredCapabilities = emptyMap(),
        constraints          = emptyList(),
        expectedOutput       = "Contract execution output",
        validationRules      = emptyList(),
        assignmentStatus     = TaskAssignmentStatus.ASSIGNED
    )

    /** Gson deserialises all numbers as Double; this helper normalises to Int. */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }

    /**
     * User-action entry trigger: appends an intent_submitted event.
     * All ledger writes are routed through [GovernanceGate]; no direct store access.
     */
    fun submitIntent(projectId: String, objective: String) {
        gate.appendEvent(
            projectId,
            EventTypes.INTENT_SUBMITTED,
            mapOf("objective" to objective)
        )
    }

    /**
     * User-action entry trigger: appends a contracts_approved event.
     * All ledger writes are routed through [GovernanceGate]; no direct store access.
     */
    fun approveContracts(projectId: String) {
        gate.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
    }
}
