package com.agoii.mobile.commit

// AGOII — COMMIT CONTRACT SYSTEM (FS-3)
// PURPOSE:
//   Provides the approval gate between ICS_COMPLETED and real-world execution.
//   No real-world action may occur without COMMIT_CONTRACT followed by user APPROVED.
//
// FLOW:
//   ICS_COMPLETED → COMMIT_CONTRACT (WRITE) → USER APPROVAL REQUIRED
//     → APPROVED  → COMMIT_EXECUTED (real-world action triggered)
//     → REJECTED  → COMMIT_ABORTED

/**
 * Approval status of a [CommitContract].
 */
enum class ApprovalStatus {
    /** Awaiting user decision. */
    PENDING,
    /** User has approved; COMMIT_EXECUTED was emitted. */
    APPROVED,
    /** User has rejected; COMMIT_ABORTED was emitted. */
    REJECTED
}

/**
 * Deterministic commit contract derived from [com.agoii.mobile.ics.IcsOutput].
 *
 * CONSTRAINTS:
 *  - Derived deterministically from the FinalArtifact via ICS output.
 *  - No AI interpretation.
 *  - No execution without [ApprovalStatus.APPROVED].
 *
 * @property reportReference      RRID linking this commit to the execution chain.
 * @property contractSetId        Contract set that produced the ICS output.
 * @property finalArtifactReference  Reference to the final assembled artifact.
 * @property proposedActions      Deterministic list of actions derived from ICS entries.
 * @property approvalStatus       Current approval status (starts as [ApprovalStatus.PENDING]).
 */
data class CommitContract(
    val reportReference:        String,
    val contractSetId:          String,
    val finalArtifactReference: String,
    val proposedActions:        List<String>,
    val approvalStatus:         ApprovalStatus = ApprovalStatus.PENDING
)
