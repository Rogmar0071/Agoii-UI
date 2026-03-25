package com.agoii.mobile

import com.agoii.mobile.assembly.AssemblyResult
import com.agoii.mobile.assembly.AssemblyValidator
import com.agoii.mobile.core.AssemblyStructuralState
import com.agoii.mobile.core.ContractStructuralState
import com.agoii.mobile.core.ExecutionStructuralState
import com.agoii.mobile.core.IntentStructuralState
import com.agoii.mobile.core.ReplayStructuralState
import org.junit.Assert.*
import org.junit.Test

/**
 * Acceptance tests for the Assembly validation contract.
 *
 * Verified invariants (per ASSEMBLY_CONSOLIDATION_AND_ENFORCEMENT_V1):
 *  1. Valid system → passes assembly.
 *  2. Partial execution → fails assembly.
 *  3. Illegal transition → fails assembly.
 *  4. Deterministic output (same input → same result).
 *  5. No mutation occurs inside AssemblyValidator.
 */
class AssemblyValidationTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Minimal fully-valid ReplayStructuralState for a completed execution system. */
    private fun validState(
        totalTasks: Int = 1
    ) = ReplayStructuralState(
        intent    = IntentStructuralState(structurallyComplete = true),
        contracts = ContractStructuralState(generated = true, valid = true),
        execution = ExecutionStructuralState(
            totalTasks     = totalTasks,
            assignedTasks  = totalTasks,
            completedTasks = totalTasks,
            validatedTasks = totalTasks,
            fullyExecuted  = true
        ),
        assembly  = AssemblyStructuralState(
            assemblyStarted   = true,
            assemblyValidated = true,
            assemblyCompleted = true,
            assemblyValid     = true
        )
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
    fun `valid three-task system passes assembly`() {
        val result = validator.validate(validState(totalTasks = 3))
        assertTrue(result.isValid)
        assertEquals("COMPLETE", result.completionStatus)
    }

    // ── 2. Partial execution → fails ──────────────────────────────────────────

    @Test
    fun `partial execution — execution not completed — fails assembly`() {
        val state = validState().copy(
            execution = ExecutionStructuralState(3, 3, 3, 3, fullyExecuted = false)
        )
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertEquals("INCOMPLETE", result.completionStatus)
        assertTrue(result.failedChecks.any { "execution_completed" in it })
    }

    @Test
    fun `partial execution — execution not started — fails assembly`() {
        val state = validState().copy(
            execution = ExecutionStructuralState(0, 0, 0, 0, fullyExecuted = false)
        )
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "execution" in it })
    }

    // ── 3. Illegal transition → fails ─────────────────────────────────────────

    @Test
    fun `assembly_started before execution_completed is an illegal transition`() {
        val state = ReplayStructuralState(
            intent    = IntentStructuralState(structurallyComplete = true),
            contracts = ContractStructuralState(generated = true, valid = true),
            execution = ExecutionStructuralState(1, 1, 0, 0, fullyExecuted = false),
            assembly  = AssemblyStructuralState(
                assemblyStarted   = true,
                assemblyValidated = false,
                assemblyCompleted = false,
                assemblyValid     = false
            )
        )
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "assembly_started" in it || "execution_completed" in it })
    }

    @Test
    fun `assembly_validated before assembly_started is an illegal transition`() {
        val state = ReplayStructuralState(
            intent    = IntentStructuralState(structurallyComplete = true),
            contracts = ContractStructuralState(generated = true, valid = true),
            execution = ExecutionStructuralState(1, 1, 1, 1, fullyExecuted = true),
            assembly  = AssemblyStructuralState(
                assemblyStarted   = false,
                assemblyValidated = true,
                assemblyCompleted = false,
                assemblyValid     = false
            )
        )
        val result = validator.validate(state)
        assertFalse(result.isValid)
        assertTrue(result.failedChecks.any { "assembly_validated" in it || "assembly_started" in it })
    }

    // ── 4. Deterministic output (same input → same result) ────────────────────

    @Test
    fun `valid state produces identical results on repeated calls`() {
        val state   = validState(totalTasks = 3)
        val first   = validator.validate(state)
        val second  = validator.validate(state)
        assertEquals(first.isValid,          second.isValid)
        assertEquals(first.completionStatus, second.completionStatus)
        assertEquals(first.missingElements,  second.missingElements)
        assertEquals(first.failedChecks,     second.failedChecks)
    }

    @Test
    fun `invalid state produces identical results on repeated calls`() {
        val state   = validState().copy(
            execution = ExecutionStructuralState(1, 1, 1, 1, fullyExecuted = false)
        )
        val first   = validator.validate(state)
        val second  = validator.validate(state)
        assertEquals(first.isValid,          second.isValid)
        assertEquals(first.completionStatus, second.completionStatus)
        assertEquals(first.missingElements,  second.missingElements)
        assertEquals(first.failedChecks,     second.failedChecks)
    }

    // ── 5. No mutation occurs ─────────────────────────────────────────────────

    @Test
    fun `validate does not mutate the ReplayStructuralState`() {
        val state    = validState(totalTasks = 3)
        val snapshot = state.copy()
        validator.validate(state)
        assertEquals("ReplayStructuralState must not be mutated by validate()", snapshot, state)
    }

    @Test
    fun `validate returns a new AssemblyResult on every call — no shared mutable state`() {
        val state   = validState()
        val first   = validator.validate(state)
        val second  = validator.validate(state)
        assertEquals(first, second)
    }
}
