// AGOII CONTRACT — EXECUTION AUTHORITY MODULE (ONE-SHOT)
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Validate and authorize execution contracts BEFORE ledger write.
// PURE validation + authorization ONLY.
//
// MUST NOT:
// - write to ledger
// - derive contracts
// - execute tasks
// - call external systems

package com.agoii.mobile.execution

// ---------- INPUT ----------

data class ExecutionContractInput(
    val contracts: List<ExecutionContract>
)

data class ExecutionContract(
    val contractId: String,
    val name: String,
    val position: Int
)

// ---------- OUTPUT ----------

sealed class ExecutionAuthorityResult {

    data class Approved(
        val orderedContracts: List<ExecutionContract>
    ) : ExecutionAuthorityResult()

    data class Blocked(
        val reason: String
    ) : ExecutionAuthorityResult()
}

// ---------- EXECUTION AUTHORITY ----------

class ExecutionAuthority {

    fun evaluate(input: ExecutionContractInput): ExecutionAuthorityResult {

        val contracts = input.contracts

        // ---------- GUARD: INCOMPLETE CONTRACT ----------

        if (contracts.isEmpty()) {
            return ExecutionAuthorityResult.Blocked("INCOMPLETE_CONTRACT")
        }

        // ---------- RULE 1: NON-EMPTY ----------

        if (contracts.isEmpty()) {
            return ExecutionAuthorityResult.Blocked("EMPTY_CONTRACT_LIST")
        }

        // ---------- RULE 2: FIELD VALIDATION ----------

        for (contract in contracts) {

            if (contract.contractId.isBlank()) {
                return ExecutionAuthorityResult.Blocked("INVALID_CONTRACT_ID")
            }

            if (contract.name.isBlank()) {
                return ExecutionAuthorityResult.Blocked("INVALID_CONTRACT_NAME")
            }

            if (contract.position <= 0) {
                return ExecutionAuthorityResult.Blocked("INVALID_POSITION")
            }
        }

        // ---------- RULE 3: POSITION SEQUENCE ----------

        val sorted = contracts.sortedBy { it.position }

        val expectedPositions = (1..contracts.size).toList()
        val actualPositions = sorted.map { it.position }

        if (expectedPositions != actualPositions) {
            return ExecutionAuthorityResult.Blocked("INVALID_POSITION_SEQUENCE")
        }

        // ---------- RULE 4: UNIQUE CONTRACT IDS ----------

        val ids = contracts.map { it.contractId }
        if (ids.size != ids.toSet().size) {
            return ExecutionAuthorityResult.Blocked("DUPLICATE_CONTRACT_ID")
        }

        // ---------- RULE 5: DETERMINISTIC ORDER ----------

        return ExecutionAuthorityResult.Approved(sorted)
    }
}
