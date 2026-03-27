package com.agoii.mobile.execution

import com.agoii.mobile.contractors.ContractorRegistry
import com.agoii.mobile.contractors.ContractorResult
import com.agoii.mobile.contractors.RealContractorRegistry
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.governor.Governor

/**
 * ExecutionLifecycle — manages the complete execution flow including contractor invocation.
 *
 * This component activates the First Execution Loop (FEL) by coordinating:
 *  - Governor state progression
 *  - Contractor invocation at the right lifecycle point
 *
 * Flow:
 *   ExecutionEntryPoint writes CONTRACTS_GENERATED
 *   → runUntilTaskAssigned() advances Governor to TASK_ASSIGNED
 *   → ContractorExecutor.executeFromTaskAssigned() invokes contractor
 *   → runAfterInvocation() advances Governor to completion
 *
 * Rules:
 *  - Does NOT modify Governor logic
 *  - Does NOT create parallel execution paths
 *  - Does NOT introduce new event types
 *  - Contractor invocation happens AFTER TASK_ASSIGNED, BEFORE TASK_STARTED
 *  - Result remains in-memory (no ledger persistence required yet)
 *
 * This is NOT an orchestration layer — it's the natural execution coordinator
 * that manages the Governor loop and triggers contractor invocation at the
 * designated point in the lifecycle.
 */
class ExecutionLifecycle(
    private val repository: EventRepository,
    private val contractorExecutor: ContractorExecutor,
    private val registry: ContractorRegistry = RealContractorRegistry()
) {

    /**
     * Result of executing the full lifecycle.
     *
     * @property completed True when execution reached EXECUTION_COMPLETED.
     * @property contractorResults Results from contractor invocations (one per contract).
     * @property finalState The final Governor state.
     */
    data class LifecycleResult(
        val completed: Boolean,
        val contractorResults: List<ContractorResult>,
        val finalState: Governor.GovernorResult
    )

    /**
     * Run the complete execution lifecycle for a project.
     *
     * This method:
     *  1. Advances Governor until TASK_ASSIGNED
     *  2. Triggers contractor invocation
     *  3. Continues Governor to completion
     *
     * @param projectId The project identifier.
     * @return LifecycleResult with contractor results and final state.
     */
    fun runLifecycle(projectId: String): LifecycleResult {
        val governor = Governor(repository, registry)
        val contractorResults = mutableListOf<ContractorResult>()

        // Advance Governor until we hit a TASK_ASSIGNED event
        var result = advanceUntilState(governor, projectId, EventTypes.TASK_ASSIGNED)
        
        // If we reached TASK_ASSIGNED, trigger contractor invocation
        while (result == Governor.GovernorResult.ADVANCED) {
            val events = repository.loadEvents(projectId)
            val lastEvent = events.lastOrNull()
            
            if (lastEvent?.type == EventTypes.TASK_ASSIGNED) {
                // FEL activation point: invoke contractor
                val contractorResult = contractorExecutor.executeFromTaskAssigned(events, lastEvent)
                contractorResults.add(contractorResult)
            }
            
            // Continue advancing Governor
            result = governor.runGovernor(projectId)
            
            // Check if we've completed or hit another TASK_ASSIGNED
            if (result == Governor.GovernorResult.COMPLETED) {
                break
            }
        }

        return LifecycleResult(
            completed = result == Governor.GovernorResult.COMPLETED,
            contractorResults = contractorResults,
            finalState = result
        )
    }

    /**
     * Advance Governor until it reaches a specific event type.
     *
     * @param governor The Governor instance.
     * @param projectId The project identifier.
     * @param targetEventType The event type to stop at.
     * @return The final GovernorResult.
     */
    private fun advanceUntilState(
        governor: Governor,
        projectId: String,
        targetEventType: String
    ): Governor.GovernorResult {
        var result = governor.runGovernor(projectId)
        var iterations = 0
        val maxIterations = 100 // Safety limit

        while (result == Governor.GovernorResult.ADVANCED && iterations < maxIterations) {
            val events = repository.loadEvents(projectId)
            val lastEvent = events.lastOrNull()
            
            if (lastEvent?.type == targetEventType) {
                return result
            }
            
            result = governor.runGovernor(projectId)
            iterations++
        }

        return result
    }

    /**
     * Simple single-step execution for projects that need contractor invocation.
     *
     * This method advances Governor one step and checks if contractor invocation
     * is needed. Use this for incremental execution control.
     *
     * @param projectId The project identifier.
     * @return ContractorResult if invocation occurred, null otherwise.
     */
    fun stepWithInvocation(projectId: String): ContractorResult? {
        val governor = Governor(repository, registry)
        val events = repository.loadEvents(projectId)
        val lastEventBefore = events.lastOrNull()

        val result = governor.runGovernor(projectId)

        if (result == Governor.GovernorResult.ADVANCED) {
            val eventsAfter = repository.loadEvents(projectId)
            val lastEventAfter = eventsAfter.lastOrNull()

            // Check if Governor just wrote a TASK_ASSIGNED event
            if (lastEventAfter?.type == EventTypes.TASK_ASSIGNED && 
                lastEventAfter != lastEventBefore) {
                return contractorExecutor.executeFromTaskAssigned(eventsAfter, lastEventAfter)
            }
        }

        return null
    }
}
