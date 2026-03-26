package com.agoii.mobile.contracts

// ─── Contract Type Classification ────────────────────────────────────────────

/**
 * Exhaustive enumeration of all contract types recognised by the Contract System.
 *
 * No other contract type may exist anywhere in the system.
 *
 * - [COMMUNICATION] — AI ↔ User interaction mediated by ICS / Ingress layer.
 * - [EXECUTION]     — Governs contractor task execution.
 * - [VALIDATION]    — Internal completeness and structural checks.
 * - [RECOVERY]      — Defines the resolution path for a detected failure.
 * - [SIMULATION]    — Non-authoritative dry-run contracts (no ledger mutation).
 */
enum class ContractType {
    COMMUNICATION,
    EXECUTION,
    VALIDATION,
    RECOVERY,
    SIMULATION
}
