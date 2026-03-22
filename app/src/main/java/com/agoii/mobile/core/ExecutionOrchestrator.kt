package com.agoii.mobile.core

import com.agoii.mobile.governance.ContractDescriptor
import com.agoii.mobile.governance.ContractGate
import com.agoii.mobile.governance.SurfaceType

// ─── Execution Orchestrator — governance gate enforcement ────────────────────

/**
 * ExecutionOrchestrator — evaluates governance contracts BEFORE execution begins.
 *
 * Architecture (AGOII-GOVERNANCE-GATE-01):
 *   contracts_generated → (ContractGate) → execution_started
 *
 * Rules:
 *  - [executeContracts] is the SINGLE enforcement point (G5).
 *  - Governance runs ONLY at this pre-execution boundary (G2).
 *  - Pure evaluation: reads the ledger, emits no events, mutates nothing (G3, G4).
 *  - Governor is unaware of this class; Governor is not modified (G1).
 */
class ExecutionOrchestrator(
    private val eventStore: EventRepository,
    private val gate: ContractGate = ContractGate()
) {

    /** Decision produced by the governance gate for a project's contracts. */
    sealed class ExecutionDecision {
        /** All contracts passed the governance gate; execution may proceed. */
        object Approved : ExecutionDecision()

        /** At least one contract was blocked by the governance gate; execution must stop. */
        data class Rejected(val reason: String) : ExecutionDecision()
    }

    /**
     * Evaluates every contract from the [contracts_generated][EventTypes.CONTRACTS_GENERATED]
     * event through the [ContractGate] before execution is allowed to start.
     *
     * Each contract is evaluated as a [ContractDescriptor] using:
     *  - surface = LG
     *  - executionCount = contract position (1-indexed)
     *  - conditionCount = 0
     *  - validationCapacity = [Governor.VC]
     *
     * @return [ExecutionDecision.Approved] if all contracts pass, or
     *         [ExecutionDecision.Rejected] with the reason if any contract is blocked.
     */
    fun executeContracts(projectId: String): ExecutionDecision {
        val events = eventStore.loadEvents(projectId)

        val contractsGenEvent = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?: return ExecutionDecision.Rejected("No ${EventTypes.CONTRACTS_GENERATED} event found in ledger")

        @Suppress("UNCHECKED_CAST")
        val contractList = (contractsGenEvent.payload["contracts"] as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?: emptyList()

        val total = if (contractList.isNotEmpty()) {
            contractList.size
        } else {
            // Fall back to the explicit total field when the contracts list is absent.
            resolveTotal(contractsGenEvent.payload["total"]) ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
        }

        for (position in 1..total) {
            val descriptor = ContractDescriptor(
                surface = SurfaceType.LG,
                executionCount = position,
                conditionCount = 0,
                validationCapacity = Governor.VC
            )
            if (!gate.approve(descriptor)) {
                return ExecutionDecision.Rejected(
                    "Contract at position $position rejected by governance gate (EL exceeds VC=${Governor.VC})"
                )
            }
        }

        return ExecutionDecision.Approved
    }

    /** Normalises the raw `total` payload value to an Int (Gson deserialises numbers as Double). */
    private fun resolveTotal(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
