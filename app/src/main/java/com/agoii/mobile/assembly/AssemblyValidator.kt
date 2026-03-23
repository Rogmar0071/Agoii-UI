package com.agoii.mobile.assembly

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventTypes

/**
 * Pure, ledger-driven validation layer for the Assembly phase.
 *
 * Rules:
 *  - Operates ONLY on the provided event list — no external inputs, no hidden state.
 *  - Does NOT execute or recompute logic; it only verifies structural integrity.
 *  - No events are emitted; no ledger mutations occur.
 *
 * Validation checks:
 *  1. execution_completed event exists before any assembly event.
 *  2. Total completed contracts equals the expected total.
 *  3. No contract position gaps.
 *  4. At least one contract_completed event exists.
 *  5. No illegal assembly state transitions.
 */
class AssemblyValidator {

    fun validate(events: List<Event>): AssemblyValidationResult {
        val errors = mutableListOf<String>()

        val executionCompletedIdx = events.indexOfLast { it.type == EventTypes.EXECUTION_COMPLETED }
        val assemblyStartedIdx    = events.indexOfFirst { it.type == EventTypes.ASSEMBLY_STARTED }

        // Rule 1: execution_completed must exist
        if (executionCompletedIdx < 0) {
            errors.add("execution_completed not found in ledger")
        }

        // Rule 1b: assembly_started must not precede execution_completed
        if (assemblyStartedIdx >= 0 && executionCompletedIdx >= 0
            && executionCompletedIdx > assemblyStartedIdx
        ) {
            errors.add("assembly_started appeared before execution_completed")
        }

        // Derive expected total contracts from ledger
        val totalContracts = deriveTotalContracts(events)

        // Count completed contracts in the ledger
        val completedContracts = events.count { it.type == EventTypes.CONTRACT_COMPLETED }

        // Rule 2: At least one contract_completed must exist
        if (completedContracts == 0) {
            errors.add("no contract_completed events found in ledger")
        }

        // Rule 3: Total completed contracts must equal expected total
        if (completedContracts != totalContracts) {
            errors.add(
                "expected $totalContracts completed contracts but found $completedContracts"
            )
        }

        // Rule 4: No contract position gaps
        val positions = events
            .filter { it.type == EventTypes.CONTRACT_COMPLETED }
            .mapNotNull { resolveInt(it.payload["position"]) }
            .sorted()

        if (positions.isNotEmpty()) {
            for (i in positions.indices) {
                if (positions[i] != i + 1) {
                    errors.add(
                        "contract position gap: expected position ${i + 1} but found ${positions[i]}"
                    )
                    break
                }
            }
        }

        // Rule 5: No illegal assembly state transitions
        validateAssemblyTransitions(events, errors)

        return AssemblyValidationResult(isValid = errors.isEmpty(), errors = errors)
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deriveTotalContracts(events: List<Event>): Int {
        val fromGenerated = events
            .firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
            ?.payload?.get("total")
        val fromStarted = events
            .firstOrNull { it.type == EventTypes.EXECUTION_STARTED }
            ?.payload?.get("total_contracts")
        return resolveInt(fromGenerated)
            ?: resolveInt(fromStarted)
            ?: EventTypes.DEFAULT_TOTAL_CONTRACTS
    }

    private fun validateAssemblyTransitions(events: List<Event>, errors: MutableList<String>) {
        for (i in events.indices) {
            val type = events[i].type
            val prior = events.take(i)
            when (type) {
                EventTypes.ASSEMBLY_STARTED -> {
                    if (prior.none { it.type == EventTypes.EXECUTION_COMPLETED }) {
                        errors.add("assembly_started appeared before execution_completed")
                    }
                }
                EventTypes.ASSEMBLY_VALIDATED -> {
                    if (prior.none { it.type == EventTypes.ASSEMBLY_STARTED }) {
                        errors.add("assembly_validated appeared before assembly_started")
                    }
                }
                EventTypes.ASSEMBLY_COMPLETED -> {
                    if (prior.none { it.type == EventTypes.ASSEMBLY_VALIDATED }) {
                        errors.add("assembly_completed appeared before assembly_validated")
                    }
                }
            }
        }
    }

    /** Gson deserialises all numbers as Double; normalise to Int. */
    private fun resolveInt(value: Any?): Int? = when (value) {
        is Int    -> value
        is Double -> value.toInt()
        is Long   -> value.toInt()
        is String -> value.toIntOrNull()
        else      -> null
    }
}
