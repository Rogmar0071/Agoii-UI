package com.agoii.mobile.contractors

// ─── ResolutionBridge ─────────────────────────────────────────────────────────
//
// Backward-compatibility bridge retaining `contractors.ResolutionTrace` for
// locked call-sites (ExecutionAuthority, UniversalContractReport) that
// reference the old package path.
//
// The canonical definition lives in `contractor.ResolutionTrace`.
// This alias MUST NOT be removed until all locked references are updated.
//
// CONTRACT: CONTRACTOR_MODULE_ALIGNMENT_V2

typealias ResolutionTrace = com.agoii.mobile.contractor.ResolutionTrace
