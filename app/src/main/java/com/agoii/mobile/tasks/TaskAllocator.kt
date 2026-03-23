package com.agoii.mobile.tasks

import com.agoii.mobile.contractor.ContractorCandidate
import com.agoii.mobile.contractor.ContractorEventEmitter
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.ContractorVerificationEngine
import com.agoii.mobile.contractor.KnowledgeScout

// ─── TaskAllocator ────────────────────────────────────────────────────────────

/**
 * TaskAllocator — assigns verified contractors to every task in a [TaskGraph].
 *
 * Assignment protocol for each [Task]:
 *  1. Emit [TaskEventTypes.TASK_ASSIGNMENT_REQUESTED].
 *  2. Query [ContractorRegistry.findBestMatch] with [Task.requiredCapabilities].
 *  3a. Match found → emit [TaskEventTypes.CONTRACTOR_ASSIGNED] +
 *                          [TaskEventTypes.TASK_READY_FOR_EXECUTION].
 *                    Task status becomes ASSIGNED.
 *  3b. No match   → emit [TaskEventTypes.CONTRACTOR_NOT_FOUND] +
 *                         [TaskEventTypes.CONTRACTOR_DISCOVERY_TRIGGERED].
 *                    Trigger [KnowledgeScout] → [ContractorVerificationEngine].
 *                    If verification succeeds → register → retry assignment (once).
 *                    If still no match → emit [TaskEventTypes.TASK_BLOCKED].
 *                    Task status becomes BLOCKED.
 *
 * Rules:
 *  - No unverified contractor may be assigned.
 *  - Every state transition emits an event.
 *  - The allocator does not modify [ContractDerivation] or any contracts module type.
 */
class TaskAllocator(
    private val registry:            ContractorRegistry            = ContractorRegistry(),
    private val knowledgeScout:      KnowledgeScout                = KnowledgeScout(),
    private val verificationEngine:  ContractorVerificationEngine  = ContractorVerificationEngine(),
    private val taskEmitter:         TaskEventEmitter              = TaskEventEmitter(),
    private val contractorEmitter:   ContractorEventEmitter        = ContractorEventEmitter()
) {

    /**
     * Allocate contractors for every task in [graph].
     *
     * @return [AllocationResult] containing the updated [TaskGraph] and all emitted events.
     */
    fun allocate(graph: TaskGraph): AllocationResult {
        val allEvents  = mutableListOf<Any>() // mixed TaskEvent + ContractorEvent
        val finalTasks = mutableListOf<Task>()

        for (task in graph.tasks) {
            val (updatedTask, events) = assignTask(task)
            allEvents.addAll(events)
            finalTasks += updatedTask
        }

        val finalGraph = TaskGraph(
            contractReference = graph.contractReference,
            tasks             = finalTasks
        )
        return AllocationResult(taskGraph = finalGraph, events = allEvents)
    }

    // ─── Internal assignment logic ────────────────────────────────────────────

    private fun assignTask(task: Task): Pair<Task, List<Any>> {
        val events = mutableListOf<Any>()

        // Step 1: request assignment
        events += taskEmitter.taskAssignmentRequested(task)

        // Step 2: query registry
        val intRequirements = task.requiredCapabilities
        val match = registry.findBestMatch(intRequirements)

        if (match != null) {
            // Step 3a: match found
            val assigned = task.copy(
                assignedContractorId = match.id,
                assignmentStatus     = TaskAssignmentStatus.ASSIGNED
            )
            events += taskEmitter.contractorAssigned(assigned, match.id)
            events += taskEmitter.taskReadyForExecution(assigned)
            return assigned to events
        }

        // Step 3b: no match — trigger discovery
        events += taskEmitter.contractorNotFound(task)
        events += taskEmitter.contractorDiscoveryTriggered(task)

        // Convert int requirements back to string claims for the scout.
        val stringClaims = intRequirements.mapValues { (_, v) ->
            when (v) {
                3    -> "high"
                2    -> "medium"
                else -> "low"
            }
        }

        val candidates: List<ContractorCandidate> = knowledgeScout.discover(stringClaims)
        for (candidate in candidates) {
            events += contractorEmitter.discovered(candidate)
            events += contractorEmitter.verificationStarted(candidate)

            val verificationResult = verificationEngine.verify(candidate)
            if (verificationResult.assignedProfile != null) {
                val registrationEvent = registry.registerVerified(verificationResult.assignedProfile)
                events += registrationEvent

                // Retry assignment with newly registered contractor.
                val retryMatch = registry.findBestMatch(intRequirements)
                if (retryMatch != null) {
                    val assigned = task.copy(
                        assignedContractorId = retryMatch.id,
                        assignmentStatus     = TaskAssignmentStatus.ASSIGNED
                    )
                    events += taskEmitter.contractorAssigned(assigned, retryMatch.id)
                    events += taskEmitter.taskReadyForExecution(assigned)
                    return assigned to events
                }
            } else {
                events += contractorEmitter.rejected(candidate, verificationResult.reasons)
            }
        }

        // Still no match — task is blocked.
        events += taskEmitter.taskBlocked(task)
        return task to events
    }
}

// ─── Result ───────────────────────────────────────────────────────────────────

/**
 * Output of [TaskAllocator.allocate].
 *
 * @property taskGraph  The [TaskGraph] with updated assignment status on every task.
 * @property events     Ordered list of all events emitted (mix of [TaskEvent] and
 *                      [com.agoii.mobile.contractor.ContractorEvent]) during allocation.
 */
data class AllocationResult(
    val taskGraph: TaskGraph,
    val events:    List<Any>
)
