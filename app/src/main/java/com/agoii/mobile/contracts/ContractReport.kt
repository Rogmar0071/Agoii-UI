package com.agoii.mobile.contracts

// AGOII CONTRACT — CONTRACT REPORT MODEL (AERP-1)
// CONVERGENCE_HARDENING_V1 — CANONICAL DEFINITION
// CONTRACT: RCF_CONVERGENCE_HARDENING_V1_0002
//
// SINGLE SOURCE OF TRUTH for ContractReport.
// No duplicate definitions permitted elsewhere.
// traceStructure MUST be ResolutionTrace — NO generic Map.

import com.agoii.mobile.contractor.ResolutionTrace
import com.agoii.mobile.execution.ValidatedSections
import com.agoii.mobile.execution.MutationSurface

data class ContractReport(
    val reportReference: String,

    // AERP-1 REQUIRED SURFACES
    val typeInventory:      List<String>,
    val functionSignatures: List<String>,
    val logicFlow:          List<String>,
    val errorConditions:    List<String>,

    // MUST BE EXPLICIT — NO GENERIC MAP
    val traceStructure: ResolutionTrace,

    // EXECUTION OUTPUT
    val rawOutput:        String,
    val normalizedOutput: String?,
    val exitCode:         Int,

    // VALIDATION SURFACE
    val failureSurface:   List<String>,
    val policyViolations: List<String>,

    // EXECUTION CONTROL SURFACES (optional with defaults for backward compatibility)
    val validatedSections: ValidatedSections = ValidatedSections(emptySet()),
    val mutationSurface: MutationSurface = MutationSurface(emptyList(), emptyList())
)
