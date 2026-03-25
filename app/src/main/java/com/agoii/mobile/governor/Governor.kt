package com.agoii.mobile.governor

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes

/**
 * Governor — pure deterministic state machine.
 *
 * Design invariants:
 *  - [nextEvent] is the sole decision function. It is pure: given the same event
 *    list it always produces the same output. It has no side effects.
 *  - Exactly one event is decided per call.
 *  - All decisions are derived exclusively from [events] (last event + ledger history).
 *  - No external gating, no execution dependency, no contractor registry lookups.
 *
 * Full lifecycle (one nextEvent call per arrow):
 *   intent_submitted → contracts_generated → contracts_ready
 *     [user: contracts_approved]
 *   contracts_approved → execution_started
 *     → contract_started(1)
 *     → task_assigned → task_started → task_completed → task_validated
 *     → contract_completed → contract_started(2) → … → contract_started(N)
 *     → contract_completed(N) → execution_completed
 *     → assembly_started → assembly_validated → assembly_completed
 */
class Governor(private val eventStore: EventRepository) {

    companion object {
        /** Maximum task-failure retries before escalation to contract_failed. */
        const val MAX_RETRIES = 3

        /** Deterministic contractor identifier used in the absence of a registry. */
        const val DEFAULT_CONTRACTOR = "default-contractor"
    }

    /**
     * Immutable description of the next event to emit.
     *
     * @param type    Event type string from [EventTypes].
     * @param payload Key-value payload for the event.
     */
    data class NextEvent(val type: String, val payload: Map<String, Any>)

    // GovernorResult enum removed — WAITING, NO_EVENT, ADVANCED, DRIFT eliminated.
    // nextEvent() / runGovernor() return NextEvent? (null = terminal or paused).
    }

    fun runGovernor(projectId: String): GovernorResult {
        val events = eventStore.loadEvents(projectId)
        if (events.isEmpty()) return GovernorResult.NO_EVENT

        val lastEvent = events.last()
        val lastType  = lastEvent.type

        return when {
            // ── Approval gate — MUST stop; do not append anything ─────────────────
            lastType == EventTypes.CONTRACTS_READY    -> GovernorResult.WAITING_FOR_APPROVAL

            // ── Terminal state ────────────────────────────────────────────────────
            lastType == EventTypes.ASSEMBLY_COMPLETED -> GovernorResult.COMPLETED

            // ── execution_started → start the first contract ──────────────────────
            lastType == EventTypes.EXECUTION_STARTED -> {
                if (!canIssue(1)) return GovernorResult.DRIFT
                eventStore.appendEvent(
                    projectId, EventTypes.CONTRACT_STARTED,
                    mapOf(
                        "contract_id" to "contract_1",
                        "position"    to 1
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── contract_started → resolve task and assign contractor ─────────────
            // Step 1 of the task execution lifecycle.
            lastType == EventTypes.CONTRACT_STARTED -> {
                val contractId = lastEvent.payload["contract_id"] as? String
                    ?: throw IllegalStateException("Missing contract_id in contract_started payload")
                val position   = resolveInt(lastEvent.payload["position"])
                    ?: throw IllegalStateException("Missing position in contract_started payload")
                val task       = taskForContract(contractId, position)
                val contractor = registry.findBestMatch(task.requiredCapabilities)
                    ?: return GovernorResult.WAITING
                eventStore.appendEvent(
                    projectId, EventTypes.TASK_ASSIGNED,
                    mapOf(
                        "taskId"       to task.taskId,
                        "contractorId" to contractor.id,
                        "contract_id"  to contractId,
                        "position"     to position
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── task_assigned → start the task ────────────────────────────────────
            // Step 2 of the task execution lifecycle.
            lastType == EventTypes.TASK_ASSIGNED -> {
                eventStore.appendEvent(
                    projectId, EventTypes.TASK_STARTED,
                    mapOf(
                        "taskId"       to (lastEvent.payload["taskId"]
                            ?: throw IllegalStateException("Missing taskId in task_assigned payload")),
                        "contractorId" to (lastEvent.payload["contractorId"]
                            ?: throw IllegalStateException("Missing contractorId in task_assigned payload")),
                        "contract_id"  to (lastEvent.payload["contract_id"]
                            ?: throw IllegalStateException("Missing contract_id in task_assigned payload")),
                        "position"     to (lastEvent.payload["position"]
                            ?: throw IllegalStateException("Missing position in task_assigned payload"))
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── task_started → execute via contractor ─────────────────────────────
            // Step 3: Governor calls ExecutionOrchestrator (pure worker) and emits
            // task_completed on success, task_failed on execution error.
            lastType == EventTypes.TASK_STARTED -> {
                val contractId    = lastEvent.payload["contract_id"]  as? String
                    ?: throw IllegalStateException("Missing contract_id in task_started payload")
                val contractorId  = lastEvent.payload["contractorId"] as? String
                    ?: throw IllegalStateException("Missing contractorId in task_started payload")
                val taskId        = lastEvent.payload["taskId"]       as? String
                    ?: throw IllegalStateException("Missing taskId in task_started payload")
                val position      = resolveInt(lastEvent.payload["position"])
                    ?: throw IllegalStateException("Missing position in task_started payload")
                val task          = taskForContract(contractId, position, taskId)
                val contractor    = registry.allVerified().find { it.id == contractorId }
                    ?: registry.findBestMatch(task.requiredCapabilities)
                    ?: return GovernorResult.WAITING
                val result = orchestrator.execute(task, contractor)
                if (result.success) {
                    eventStore.appendEvent(
                        projectId, EventTypes.TASK_COMPLETED,
                        mapOf(
                            "taskId"       to task.taskId,
                            "contractorId" to contractor.id,
                            "contract_id"  to contractId,
                            "position"     to position
                        )
                    )
                } else {
                    eventStore.appendEvent(
                        projectId, EventTypes.TASK_FAILED,
                        mapOf(
                            "taskId"       to task.taskId,
                            "contractorId" to contractor.id,
                            "contract_id"  to contractId,
                            "position"     to position,
                            "reason"       to (result.error ?: "Execution error")
                        )
                    )
                }
                GovernorResult.ADVANCED
            }

            // ── task_completed → validate the result ──────────────────────────────
            // Step 4: Governor re-calls ExecutionOrchestrator (deterministic) to
            // validate the artifact. Emits task_validated or task_failed.
            lastType == EventTypes.TASK_COMPLETED -> {
                val contractId   = lastEvent.payload["contract_id"]  as? String
                    ?: throw IllegalStateException("Missing contract_id in task_completed payload")
                val contractorId = lastEvent.payload["contractorId"] as? String
                    ?: throw IllegalStateException("Missing contractorId in task_completed payload")
                val taskId       = lastEvent.payload["taskId"]       as? String
                    ?: throw IllegalStateException("Missing taskId in task_completed payload")
                val position     = resolveInt(lastEvent.payload["position"])
                    ?: throw IllegalStateException("Missing position in task_completed payload")
                val task         = taskForContract(contractId, position, taskId)
                val contractor   = registry.allVerified().find { it.id == contractorId }
                    ?: registry.findBestMatch(task.requiredCapabilities)
                    ?: return GovernorResult.WAITING
                val result = orchestrator.execute(task, contractor)
                if (result.validationResult.verdict == ValidationVerdict.VALIDATED) {
                    eventStore.appendEvent(
                        projectId, EventTypes.TASK_VALIDATED,
                        mapOf(
                            "taskId"      to task.taskId,
                            "contract_id" to contractId,
                            "position"    to position
                        )
                    )
                } else {
                    val reason = result.validationResult.failureReasons.joinToString("; ")
                    eventStore.appendEvent(
                        projectId, EventTypes.TASK_FAILED,
                        mapOf(
                            "taskId"       to task.taskId,
                            "contractorId" to contractor.id,
                            "contract_id"  to contractId,
                            "position"     to position,
                            "reason"       to reason
                        )
                    )
                }
                GovernorResult.ADVANCED
            }

            // ── task_validated → close the contract via execution module ─────────
            // Step 5: Contract closes ONLY after task_validated (critical rule).
            // ExecutionClosure is the sole authority for CONTRACT_COMPLETED / EXECUTION_COMPLETED.
            lastType == EventTypes.TASK_VALIDATED -> {
                val contractId = lastEvent.payload["contract_id"] as? String
                    ?: throw IllegalStateException("Missing contract_id in task_validated payload")
                val position   = resolveInt(lastEvent.payload["position"])
                    ?: throw IllegalStateException("Missing position in task_validated payload")
                executionClosure.closeContract(projectId, contractId, position)
                GovernorResult.ADVANCED
            }

            // ── task_failed → retry / reassign / escalate ─────────────────────────
            // Governor owns the retry decision; DecisionEngine is the sole decision authority.
            lastType == EventTypes.TASK_FAILED -> {
                val contractId   = lastEvent.payload["contract_id"]  as? String
                    ?: throw IllegalStateException("Missing contract_id in task_failed payload")
                val taskId       = lastEvent.payload["taskId"]       as? String
                    ?: throw IllegalStateException("Missing taskId in task_failed payload")
                val contractorId = lastEvent.payload["contractorId"] as? String
                    ?: throw IllegalStateException("Missing contractorId in task_failed payload")
                val position     = resolveInt(lastEvent.payload["position"])
                    ?: throw IllegalStateException("Missing position in task_failed payload")
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
                        eventStore.appendEvent(
                            projectId, EventTypes.TASK_ASSIGNED,
                            mapOf(
                                "taskId"       to task.taskId,
                                "contractorId" to contractor.id,
                                "contract_id"  to contractId,
                                "position"     to position
                            )
                        )
                        GovernorResult.ADVANCED
                    }
                    DecisionAction.REASSIGN -> {
                        val newContractor = outcome.assignedContractor!!
                        eventStore.appendEvent(
                            projectId, EventTypes.CONTRACTOR_REASSIGNED,
                            mapOf(
                                "taskId"               to task.taskId,
                                "previousContractorId" to contractor.id,
                                "newContractorId"      to newContractor.id,
                                "contract_id"          to contractId,
                                "position"             to position
                            )
                        )
                        GovernorResult.ADVANCED
                    }
                    DecisionAction.ESCALATE -> {
                        eventStore.appendEvent(
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
                val taskId          = lastEvent.payload["taskId"]          as? String
                    ?: throw IllegalStateException("Missing taskId in contractor_reassigned payload")
                val newContractorId = lastEvent.payload["newContractorId"] as? String
                    ?: throw IllegalStateException("Missing newContractorId in contractor_reassigned payload")
                val contractId      = lastEvent.payload["contract_id"]     as? String
                    ?: throw IllegalStateException("Missing contract_id in contractor_reassigned payload")
                val position        = resolveInt(lastEvent.payload["position"])
                    ?: throw IllegalStateException("Missing position in contractor_reassigned payload")
                eventStore.appendEvent(
                    projectId, EventTypes.TASK_ASSIGNED,
                    mapOf(
                        "taskId"       to taskId,
                        "contractorId" to newContractorId,
                        "contract_id"  to contractId,
                        "position"     to position
                    )
                )
                GovernorResult.ADVANCED
            }

            // ── contract_completed → Governor controls sequencing ─────────────────
            // Read position from payload.
            // If position < TOTAL_CONTRACTS → emit CONTRACT_STARTED(next).
            // If position == TOTAL_CONTRACTS → DO NOTHING (EXECUTION_COMPLETED was
            // already emitted by ExecutionClosure in the same closeContract() call).
            // canIssue() gates issuance via SSM activation and CSL evaluation;
            // DRIFT is returned when the next contract cannot be legally issued.
            lastType == EventTypes.CONTRACT_COMPLETED -> {
                val position = resolveInt(lastEvent.payload["position"])
                    ?: throw IllegalStateException("Missing position in contract_completed payload")
                if (position < EventTypes.DEFAULT_TOTAL_CONTRACTS) {
                    val nextPosition = position + 1
                    if (!canIssue(nextPosition)) return GovernorResult.DRIFT
                    eventStore.appendEvent(
                        projectId, EventTypes.CONTRACT_STARTED,
                        mapOf(
                            "contract_id" to "contract_$nextPosition",
                            "position"    to nextPosition
                        )
                    )
                }
                GovernorResult.ADVANCED
            }

            // ── assembly_started → validate integrity before emitting assembly_validated ─
            // AssemblyValidator is a pure, state-driven check — no mutation occurs.
            // If validation fails, assembly_validated is blocked and NO_EVENT is returned.
            lastType == EventTypes.ASSEMBLY_STARTED -> {
                val state = Replay(eventStore).replayStructuralState(projectId)
                if (!state.execution.fullyExecuted) return GovernorResult.NO_EVENT
                if (!state.contracts.generated || !state.contracts.valid) return GovernorResult.NO_EVENT
                eventStore.appendEvent(projectId, EventTypes.ASSEMBLY_VALIDATED, emptyMap())
                GovernorResult.ADVANCED
            }

            // ── Standard single-step governor transition (covers assembly pipeline) ─
            VALID_TRANSITIONS.containsKey(lastType) -> {
                val nextType = VALID_TRANSITIONS[lastType]!!
                val payload  = buildPayload(nextType, lastEvent)
                eventStore.appendEvent(projectId, nextType, payload)
                GovernorResult.ADVANCED
            }

            else -> GovernorResult.NO_EVENT
        }
    }

    /**
     * Single gate function — the only path to contract issuance.
     * Returns false (blocking issuance) if:
     *  - SSM is not initialized
     *  - LG surface is not active
     *  - CSL evaluation rejects the contract at the given position
     */
    private fun canIssue(position: Int): Boolean {
        if (!ssm.isInitialized()) return false
        if (!ssm.getActiveSurfaces().contains(SurfaceType.LG)) return false

        val result = csl.evaluate(
            ContractDescriptor(
                surface = SurfaceType.LG,
                executionCount = position,
                conditionCount = 0,
                validationCapacity = VC
            )
        )

        return result.outcome == Outcome.ALLOWED
    }

    private fun buildPayload(nextType: String, triggerEvent: Event): Map<String, Any> =
        when (nextType) {
            EventTypes.CONTRACTS_GENERATED -> {
                val intent = triggerEvent.payload["objective"] as? String
                    ?: throw IllegalStateException("Missing objective in intent_submitted payload")
                mapOf(
                    "source_intent" to intent,
                    "contracts"     to listOf(
                        mapOf("id" to "contract_1", "name" to "Core Setup"),
                        mapOf("id" to "contract_2", "name" to "Integration"),
                        mapOf("id" to "contract_3", "name" to "Validation")
                    )
                )
            }
            else -> emptyMap()
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
     * Governor is the sole authority allowed to write to the event ledger.
     */
    fun submitIntent(projectId: String, objective: String) {
        eventStore.appendEvent(
            projectId,
            EventTypes.INTENT_SUBMITTED,
            mapOf("objective" to objective)
        )
    }

    /**
     * User-action entry trigger: appends a contracts_approved event.
     * Governor is the sole authority allowed to write to the event ledger.
     */
    fun approveContracts(projectId: String) {
        eventStore.appendEvent(projectId, EventTypes.CONTRACTS_APPROVED, emptyMap())
    }
}
