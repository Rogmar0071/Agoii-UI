package com.agoii.mobile.contracts

// AGOII CONTRACT — UNIVERSAL CONTRACT NORMALIZER (UCS-1)
// SURFACE 3: CONTRACT NORMALIZATION
// CLASSIFICATION:
// - Class: Structural
// - Reversibility: Forward-only
// - Execution Scope: Both
// - Mutation Authority: Tier C
//
// PURPOSE:
// Produces the canonical (normalized) form of a [UniversalContract].
//
// NORMALIZATION RULES:
//   N1 — String trimming: all string fields stripped of leading/trailing whitespace.
//   N2 — Deduplication: requiredCapabilities and constraints deduplicated (preserving first occurrence).
//   N3 — Schema key ordering: expectedSchema entries sorted by key ascending.
//   N4 — Identity (idempotency): normalizing an already-normalized contract is a no-op.

/**
 * UniversalContractNormalizer — canonical-form producer for [UniversalContract].
 *
 * Entry point: [normalize].
 *
 * Normalization is a pure function: same input → same output, no side effects.
 * The original contract is never mutated; a new [UniversalContract] is always returned.
 *
 * Normalization rules applied in order:
 *  N1 — Trim: strip leading/trailing whitespace from all string scalars.
 *  N2 — Deduplicate: remove duplicate items from [UniversalContract.requiredCapabilities] and
 *       [UniversalContract.constraints], preserving the order of first occurrence.
 *  N3 — Sort schema: sort [OutputDefinition.expectedSchema] entries by key ascending,
 *       and trim each key.
 *  N4 — Idempotency: applying [normalize] to an already-normalized contract returns an
 *       equal contract.
 */
class UniversalContractNormalizer {

    /**
     * Produce the canonical form of [contract].
     *
     * @param contract The contract to normalize.
     * @return A new [UniversalContract] with all normalization rules applied.
     *         Structural invariants (position ≥ 1, total ≥ position, etc.) are
     *         preserved; no fields are dropped.
     */
    fun normalize(contract: UniversalContract): UniversalContract {
        // N1 — trim all string scalars
        val contractId      = contract.contractId.trim()
        val intentId        = contract.intentId.trim()
        val reportReference = contract.reportReference.trim()

        // N2 — deduplicate requiredCapabilities (preserve insertion order) and constraints
        val requiredCapabilities = contract.requiredCapabilities.distinct()
        val constraints  = deduplicateList(contract.constraints)

        // N3 — sort outputDefinition.expectedSchema keys; trim each key
        val sortedSchema = contract.outputDefinition.expectedSchema
            .entries
            .sortedBy { it.key }
            .associate { it.key.trim() to it.value }

        val outputDefinition = OutputDefinition(
            expectedType   = contract.outputDefinition.expectedType.trim(),
            expectedSchema = sortedSchema
        )

        return UniversalContract(
            contractId            = contractId,
            intentId              = intentId,
            reportReference       = reportReference,
            contractClass         = contract.contractClass,
            executionType         = contract.executionType,
            targetDomain          = contract.targetDomain,
            position              = contract.position,
            total                 = contract.total,
            requiredCapabilities  = requiredCapabilities,
            constraints           = constraints,
            outputDefinition      = outputDefinition
        )
    }

    /**
     * Deduplicate [items] while preserving the order of first occurrences (N2).
     *
     * Items are compared by their string representation after lowercasing and trimming,
     * so that whitespace differences and case variations do not produce duplicates.
     */
    private fun deduplicateList(items: List<Any>): List<Any> {
        val seen = mutableSetOf<String>()
        return items.filter { item ->
            seen.add(item.toString().lowercase().trim())
        }
    }
}
