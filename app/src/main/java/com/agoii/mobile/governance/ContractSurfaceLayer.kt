package com.agoii.mobile.governance

/**
 * CSL — Contract Surface Layer
 *
 * Preventative governance enforcement gate that validates every contract
 * before it may be issued (before contract_started is appended to the ledger).
 *
 * Enforcement rules (applied in order — first failing rule wins):
 *
 *   Rule 0 — SSM guard:
 *     The State Surface Mirror must be initialized before any contract may pass.
 *     Rejection code: SSM_NOT_INITIALIZED
 *
 *   Rule 1 — EL ≤ VC:
 *     Execution Load must not exceed Validation Capacity.
 *     A contract whose EL > VC exceeds the system's ability to validate it
 *     and cannot be safely executed.
 *     Rejection code: EL_EXCEEDS_VC
 *
 *   Rule 2 — Single-surface constraint:
 *     A contract must touch exactly one functional surface.
 *     Contracts spanning multiple surfaces create coupling across domain
 *     boundaries and are structurally prohibited.
 *     Rejection code: MULTI_SURFACE_BLOCKED
 *
 * All rules are deterministic; no side effects.
 *
 * @param ssm The [StateSurfaceMirror] providing internal state awareness.
 */
open class ContractSurfaceLayer(private val ssm: StateSurfaceMirror) {

    /**
     * Evaluate [contract] against all CSL enforcement rules.
     *
     * @return [CslResult.allowed] = true  → contract may be issued.
     *         [CslResult.allowed] = false → issuance blocked; see [CslResult.rejectionReason].
     */
    open fun evaluateContract(contract: ContractDescriptor): CslResult {

        // Rule 0: SSM must be initialized
        if (!ssm.isInitialized()) {
            return CslResult(
                allowed         = false,
                rejectionReason = "SSM_NOT_INITIALIZED: state surface mirror is not active"
            )
        }

        // Rule 1: EL ≤ VC
        if (contract.executionLoad > contract.validationCapacity) {
            return CslResult(
                allowed         = false,
                rejectionReason = "EL_EXCEEDS_VC: executionLoad=${contract.executionLoad} " +
                    "exceeds validationCapacity=${contract.validationCapacity}"
            )
        }

        // Rule 2: single-surface constraint
        if (contract.surfaces.size > 1) {
            val surfaceNames = contract.surfaces.joinToString(", ") { it.name }
            return CslResult(
                allowed         = false,
                rejectionReason = "MULTI_SURFACE_BLOCKED: contract '${contract.contractId}' " +
                    "spans ${contract.surfaces.size} surfaces [$surfaceNames]"
            )
        }

        return CslResult(allowed = true)
    }
}
