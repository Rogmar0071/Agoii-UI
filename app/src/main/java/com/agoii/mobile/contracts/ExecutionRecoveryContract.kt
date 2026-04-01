package com.agoii.mobile.contracts

import com.agoii.mobile.execution.AnchorState

// AGOII CONTRACT — EXECUTION RECOVERY CONTRACT (FSE-2 / RCF-1)
// CLASS: OPERATIONAL
// REVERSIBILITY: FORWARD_ONLY
//
// Canonical recovery contract for violation-based execution failures.
// ONE contract per violation. ALL fields required. No optional fields.
// Derived exclusively from ContractReport at the moment of validation.

data class ExecutionRecoveryContract(
    val reportReference: String,
    val contractId: String,
    val failureClass: FailureClass,
    val violationField: String,
    val correctionDirective: String,
    val anchorState: AnchorState,
    val successCondition: String
)
