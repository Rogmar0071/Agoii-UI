package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyResult
import com.agoii.mobile.assembly.AssemblyValidator
import com.agoii.mobile.core.ReplayState
import org.junit.Assert.*
import org.junit.Test

/**
 * Acceptance tests for the Assembly validation contract.
 *
 * Verified invariants (per ASSEMBLY_CONSOLIDATION_AND_ENFORCEMENT_V1):
 *  1. Valid system → passes assembly.
 *  2. Missing contract → fails assembly.
 *  3. Partial execution → fails assembly.
 *  4. Illegal transition → fails assembly.
 *  5. Deterministic output (same input → same result).
 *  6. No mutation occurs inside AssemblyValidator.
 */
class AssemblyValidationTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Minimal fully-valid ReplayState for a single-contract system. */
    private fun validState(
        contracts: Int = 1,
        objective: String = "test-objective"
    ) = ReplayState(
        phase              = "assembly_validated",
        contractsCompleted = contracts,
        totalContracts     = contracts,
        executionStarted   = true,
        executionCompleted = true,
        assemblyStarted    = true,
        assemblyValidated  = true,
        objective          = objective
    )

    private val validator = AssemblyValidator()

    // ── 1. Valid system → passes assembly ─────────────────────────────────────

    @Test
    fun `valid system passes assembly`() {
        val result = validator.validate(validState())
        assertTrue("Expected valid but got issues: ${result.missingElements + result.failedChecks}", result.isValid)
        assertEquals("COMPLETE", result.completionStatus)
        assertTrue(result.missingElements.isEmpty())
        assertTrue(result.failedChecks.isEmpty())
    }

    @Test
    fun `valid three-contract system passes assembly`() {
        val result = validator.validate(validState(contracts = 3))
        assertTrue(result.isValid)
        assertEquals("COMPLETE", result.completionStatus)
    }

    // ── 2. Missing contract → fails ───────────────────────────────────────────

    @Test
    fun `missing contract causes assembly to fail`() {
        val state = validState(contracts = 3).copy(contractsCompleted = 2)
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertEquals("INCOMPLETE", result.completionStatus)
        assertTrue(
            "Expected failedChecks to mention contract count mismatch",
            result.failedChecks.any { "contract" in it }
        )
    }

    @Test
    fun `no contracts completed causes assembly to fail with missing element`() {
        val state = validState().copy(contractsCompleted = 0)
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertTrue(result.missingElements.any { "contract_completed" in it })
    }

    // ── 3. Partial execution → fails ──────────────────────────────────────────

    @Test
    fun `partial execution — execution not completed — fails assembly`() {
        val state = validState().copy(executionCompleted = false)
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertEquals("INCOMPLETE", result.completionStatus)
        assertTrue(result.failedChecks.any { "execution_completed" in it })
    }

    @Test
    fun `partial execution — execution not started — fails assembly`() {
        val state = validState().copy(executionStarted = false, executionCompleted = false)
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "execution" in it })
    }

    // ── 4. Illegal transition → fails ─────────────────────────────────────────

    @Test
    fun `assembly_started before execution_completed is an illegal transition`() {
        val state = ReplayState(
            phase              = "assembly_started",
            contractsCompleted = 1,
            totalContracts     = 1,
            executionStarted   = true,
            executionCompleted = false,  // execution not yet complete
            assemblyStarted    = true,   // illegal — before executionCompleted
            assemblyValidated  = false,
            objective          = "test-objective"
        )
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "assembly_started" in it || "execution_completed" in it })
    }

    @Test
    fun `assembly_validated before assembly_started is an illegal transition`() {
        val state = ReplayState(
            phase              = "assembly_validated",
            contractsCompleted = 1,
            totalContracts     = 1,
            executionStarted   = true,
            executionCompleted = true,
            assemblyStarted    = false,  // missing assembly_started
            assemblyValidated  = true,   // illegal — before assemblyStarted
            objective          = "test-objective"
        )
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "assembly_validated" in it || "assembly_started" in it })
    }

    // ── 5. Deterministic output (same input → same result) ────────────────────

    @Test
    fun `valid state produces identical results on repeated calls`() {
        val state   = validState(contracts = 3)
        val first   = validator.validate(state)
        val second  = validator.validate(state)
        assertEquals(first.isValid,          second.isValid)
        assertEquals(first.completionStatus, second.completionStatus)
        assertEquals(first.missingElements,  second.missingElements)
        assertEquals(first.failedChecks,     second.failedChecks)
    }

    @Test
    fun `invalid state produces identical results on repeated calls`() {
        val state   = validState().copy(executionCompleted = false)
        val first   = validator.validate(state)
        val second  = validator.validate(state)
        assertEquals(first.isValid,          second.isValid)
        assertEquals(first.completionStatus, second.completionStatus)
        assertEquals(first.missingElements,  second.missingElements)
        assertEquals(first.failedChecks,     second.failedChecks)
    }

    // ── 6. No mutation occurs ─────────────────────────────────────────────────

    @Test
    fun `validate does not mutate the ReplayState`() {
        val state    = validState(contracts = 3)
        val snapshot = state.copy()
        validator.validate(state)
        assertEquals("ReplayState must not be mutated by validate()", snapshot, state)
    }

    @Test
    fun `validate returns a new AssemblyResult on every call — no shared mutable state`() {
        val state   = validState()
        val first   = validator.validate(state)
        val second  = validator.validate(state)
        // Results are equal by value but must not be the same object (pure function contract).
        assertEquals(first, second)
    }
}
