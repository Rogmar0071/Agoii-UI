package com.agoii.mobile.ics

import com.agoii.mobile.assembly.FinalArtifact

// ── Output classification ─────────────────────────────────────────────────────

/**
 * Classification of a single ICS output entry.
 *
 * Determined purely from the artifact structure — no AI rewriting, no inference.
 */
enum class IcsOutputType {
    /** Entry carries factual, read-only information. */
    INFORMATIONAL,
    /** Entry describes a required action or next step. */
    ACTIONABLE,
    /** Entry describes an error, warning, or diagnostic condition. */
    DIAGNOSTIC
}

// ── Input model (ledger-derived only) ────────────────────────────────────────

/**
 * Ledger-derived input for the ICS Module.
 *
 * ALL fields MUST be reconstructed from the EventLedger exclusively.
 * No cached state, no external inference (RRIL-1).
 */
data class IcsInput(
    val reportReference:        String,
    val contractSetId:          String,
    val totalContracts:         Int,
    val finalArtifactReference: String,
    val finalArtifact:          FinalArtifact
)

// ── Output models ─────────────────────────────────────────────────────────────

/**
 * A single normalized output entry within the ICS output.
 *
 * Trace fields ([contractId], [artifactReference]) preserve RRIL-1 lineage.
 */
data class IcsOutputEntry(
    val contractId:        String,
    val position:          Int,
    val artifactReference: String,
    val outputType:        IcsOutputType,
    /** Normalized field map: execution-only metadata removed, names standardized. */
    val fields:            Map<String, Any>
)

/**
 * A deterministic grouping of [IcsOutputEntry] items in the structured ICS output.
 *
 * Sections: summary, details, actions.
 */
data class IcsOutputSection(
    val name:    String,
    val entries: List<IcsOutputEntry>
)

/**
 * Final structured, externally consumable ICS output.
 *
 * [taskId] is always "ICS::<reportReference>".
 * [icsOutputReference] is the deterministic reference for this output.
 *
 * Structure:
 *  - [summary]  — first contract's INFORMATIONAL entry (overview)
 *  - [details]  — all INFORMATIONAL entries
 *  - [actions]  — all ACTIONABLE entries (omitted from [sections] when empty)
 *  - [diagnostics] — all DIAGNOSTIC entries (omitted from [sections] when empty)
 */
data class IcsOutput(
    val reportReference:    String,
    val taskId:             String,
    val icsOutputReference: String,
    val totalContracts:     Int,
    /** All normalized entries, ordered by contract position. */
    val entries:            List<IcsOutputEntry>,
    /** Grouped sections: always contains summary + details; actions/diagnostics when present. */
    val sections:           List<IcsOutputSection>
)

// ── Result ────────────────────────────────────────────────────────────────────

/**
 * Result of [IcsModule.process].
 */
sealed class IcsExecutionResult {

    /** ICS processing completed; ICS_COMPLETED written to ledger. */
    data class Processed(val icsOutput: IcsOutput) : IcsExecutionResult()

    /**
     * Trigger conditions not met — last event is not ASSEMBLY_COMPLETED,
     * or required payload fields are absent.  No ledger writes occur.
     */
    object NotTriggered : IcsExecutionResult()

    /**
     * ICS_COMPLETED already exists for this [reportReference].
     * Idempotency guard; no ledger writes occur.
     */
    data class AlreadyCompleted(val reportReference: String) : IcsExecutionResult()

    /**
     * FinalArtifact reconstruction failed — required data is missing from the ledger.
     * No ledger writes occur.
     */
    data class Blocked(val reason: String) : IcsExecutionResult()
}
