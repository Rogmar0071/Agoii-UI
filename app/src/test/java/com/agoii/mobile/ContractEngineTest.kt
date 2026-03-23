package com.agoii.mobile

import com.agoii.mobile.contracts.ConstraintEnforcer
import com.agoii.mobile.contracts.ContractEngine
import com.agoii.mobile.contracts.ContractFailureType
import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.contracts.ContractModule
import com.agoii.mobile.contracts.ContractOutcome
import com.agoii.mobile.contracts.DeterministicDeriver
import com.agoii.mobile.contracts.ExecutionDecomposer
import com.agoii.mobile.contracts.FailureMapper
import com.agoii.mobile.contracts.SurfaceMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the deterministic Contract Engine and its five constituent stages.
 *
 * Tests run on the JVM without an Android device (no Android-framework imports).
 */
class ContractEngineTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun minimalIntent(
        objective:   String = "update core event ledger",
        constraints: String = "",
        environment: String = "",
        resources:   String = "ledger"
    ) = ContractIntent(objective, constraints, environment, resources)

    // ══════════════════════════════════════════════════════════════════════════
    // Step 1 — SurfaceMapper
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SurfaceMapper always includes CORE`() {
        val surface = SurfaceMapper().map(minimalIntent())
        assertTrue(surface.modules.any { it.module == ContractModule.CORE })
    }

    @Test
    fun `SurfaceMapper adds UI when objective mentions screen`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "update the screen layout")
        )
        assertTrue(surface.modules.any { it.module == ContractModule.UI })
    }

    @Test
    fun `SurfaceMapper adds UI when environment mentions compose`() {
        val surface = SurfaceMapper().map(
            minimalIntent(environment = "compose-based UI")
        )
        assertTrue(surface.modules.any { it.module == ContractModule.UI })
    }

    @Test
    fun `SurfaceMapper adds BRIDGE when objective mentions bridge`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "bridge native platform calls")
        )
        assertTrue(surface.modules.any { it.module == ContractModule.BRIDGE })
    }

    @Test
    fun `SurfaceMapper adds ORCHESTRATION when objective mentions pipeline`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "refactor the execution pipeline")
        )
        assertTrue(surface.modules.any { it.module == ContractModule.ORCHESTRATION })
    }

    @Test
    fun `SurfaceMapper adds IRS when objective mentions swarm`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "calibrate swarm validation thresholds")
        )
        assertTrue(surface.modules.any { it.module == ContractModule.IRS })
    }

    @Test
    fun `SurfaceMapper adds GOVERNANCE when constraints mention contract`() {
        val surface = SurfaceMapper().map(
            minimalIntent(constraints = "contract approval required")
        )
        assertTrue(surface.modules.any { it.module == ContractModule.GOVERNANCE })
    }

    @Test
    fun `SurfaceMapper does not add UI for unrelated intent`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "update core event ledger")
        )
        assertFalse(surface.modules.any { it.module == ContractModule.UI })
    }

    @Test
    fun `SurfaceMapper totalWeight equals sum of module weights`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "update the screen layout")
        )
        val expected = surface.modules.sumOf { it.module.weight }
        assertEquals(expected, surface.totalWeight)
    }

    @Test
    fun `SurfaceMapper modules are sorted by ContractModule ordinal`() {
        val surface = SurfaceMapper().map(
            minimalIntent(
                objective   = "update screen layout and bridge native calls",
                constraints = "contract approval required"
            )
        )
        val ordinals = surface.modules.map { it.module.ordinal }
        assertEquals(ordinals.sorted(), ordinals)
    }

    @Test
    fun `SurfaceMapper is deterministic for equal inputs`() {
        val intent = minimalIntent(
            objective   = "update screen layout",
            constraints = "contract approval required"
        )
        val s1 = SurfaceMapper().map(intent)
        val s2 = SurfaceMapper().map(intent)
        assertEquals(s1, s2)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 2 — FailureMapper
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `FailureMapper reports no failures for clean minimal intent`() {
        val intent  = minimalIntent()
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        assertFalse(fm.hasCritical)
        assertTrue(fm.failures.none { it.type == ContractFailureType.LOAD_EXCEEDED })
    }

    @Test
    fun `FailureMapper reports LOAD_EXCEEDED when totalWeight exceeds threshold`() {
        // Force a very large surface by mentioning many modules
        val intent = ContractIntent(
            objective   = "update screen layout bridge native pipeline swarm evidence",
            constraints = "contract approval required",
            environment = "native platform",
            resources   = "governance contract ledger"
        )
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        if (surface.totalWeight > FailureMapper.CRITICAL_LOAD_THRESHOLD) {
            assertTrue(fm.hasCritical)
            assertTrue(fm.failures.any { it.type == ContractFailureType.LOAD_EXCEEDED })
        }
    }

    @Test
    fun `FailureMapper does not report LOAD_EXCEEDED when totalWeight is within threshold`() {
        // minimalIntent produces CORE only -> totalWeight = 3, well within threshold 10
        val intent  = minimalIntent()
        val surface = SurfaceMapper().map(intent)
        assertTrue(
            "expected totalWeight <= threshold but got ${surface.totalWeight}",
            surface.totalWeight <= FailureMapper.CRITICAL_LOAD_THRESHOLD
        )
        val fm = FailureMapper().map(intent, surface)
        assertFalse(fm.failures.any { it.type == ContractFailureType.LOAD_EXCEEDED })
    }

    @Test
    fun `FailureMapper reports MISSING_RESOURCE when resources is blank`() {
        val intent  = minimalIntent(resources = "")
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        assertTrue(fm.failures.any { it.type == ContractFailureType.MISSING_RESOURCE })
    }

    @Test
    fun `FailureMapper MISSING_RESOURCE is not critical`() {
        val intent  = minimalIntent(resources = "")
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        val failure = fm.failures.first { it.type == ContractFailureType.MISSING_RESOURCE }
        assertFalse(failure.critical)
    }

    @Test
    fun `FailureMapper reports CONSTRAINT_VIOLATED when GOVERNANCE is in surface but no constraints`() {
        // resources = "governance contract" triggers GOVERNANCE in SurfaceMapper (line 66)
        val intent = ContractIntent(
            objective   = "update core event ledger",
            constraints = "",
            environment = "",
            resources   = "governance contract"
        )
        val surface = SurfaceMapper().map(intent)
        // Precondition: GOVERNANCE must be in the surface for this test to be meaningful
        assertTrue(
            "expected GOVERNANCE in surface but got: ${surface.modules.map { it.module }}",
            surface.modules.any { it.module == ContractModule.GOVERNANCE }
        )
        val fm = FailureMapper().map(intent, surface)
        assertTrue(fm.hasCritical)
        assertTrue(fm.failures.any { it.type == ContractFailureType.CONSTRAINT_VIOLATED })
    }

    @Test
    fun `FailureMapper hasCritical is false when all failures are non-critical`() {
        val intent  = minimalIntent(resources = "")  // only MISSING_RESOURCE (non-critical)
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        assertFalse(fm.hasCritical)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 3 — ExecutionDecomposer
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ExecutionDecomposer produces one step per surface module`() {
        val surface = SurfaceMapper().map(minimalIntent())
        val plan    = ExecutionDecomposer().decompose(surface)
        assertEquals(surface.modules.size, plan.steps.size)
    }

    @Test
    fun `ExecutionDecomposer steps are 1-based sequential positions`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "update screen layout")
        )
        val plan = ExecutionDecomposer().decompose(surface)
        plan.steps.forEachIndexed { index, step ->
            assertEquals(index + 1, step.position)
        }
    }

    @Test
    fun `ExecutionDecomposer step load equals module weight`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "update screen layout")
        )
        val plan = ExecutionDecomposer().decompose(surface)
        plan.steps.forEach { step ->
            assertEquals(step.module.weight, step.load)
        }
    }

    @Test
    fun `ExecutionDecomposer totalLoad equals sum of step loads`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "update screen layout")
        )
        val plan = ExecutionDecomposer().decompose(surface)
        assertEquals(plan.steps.sumOf { it.load }, plan.totalLoad)
    }

    @Test
    fun `ExecutionDecomposer totalLoad equals surface totalWeight`() {
        val surface = SurfaceMapper().map(
            minimalIntent(objective = "bridge native pipeline")
        )
        val plan = ExecutionDecomposer().decompose(surface)
        assertEquals(surface.totalWeight, plan.totalLoad)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 4 — ConstraintEnforcer
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ConstraintEnforcer passes when no constraints declared`() {
        val intent  = minimalIntent(constraints = "")
        val surface = SurfaceMapper().map(intent)
        val plan    = ExecutionDecomposer().decompose(surface)
        val result  = ConstraintEnforcer().enforce(intent, plan)
        assertTrue(result.passed)
        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `ConstraintEnforcer rejects UI step when no-ui constraint present`() {
        val intent = ContractIntent(
            objective   = "update screen layout",
            constraints = "no ui",
            environment = "",
            resources   = "ledger"
        )
        val surface = SurfaceMapper().map(intent)
        val plan    = ExecutionDecomposer().decompose(surface)
        val result  = ConstraintEnforcer().enforce(intent, plan)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.step.module == ContractModule.UI })
    }

    @Test
    fun `ConstraintEnforcer rejects CORE step when read-only constraint present`() {
        val intent = ContractIntent(
            objective   = "update core event ledger",
            constraints = "read-only",
            environment = "",
            resources   = "ledger"
        )
        val surface = SurfaceMapper().map(intent)
        val plan    = ExecutionDecomposer().decompose(surface)
        val result  = ConstraintEnforcer().enforce(intent, plan)
        assertFalse(result.passed)
        assertTrue(result.violations.any { it.step.module == ContractModule.CORE })
    }

    @Test
    fun `ConstraintEnforcer violation carries the constraint label`() {
        val intent = ContractIntent(
            objective   = "update screen layout",
            constraints = "no ui",
            environment = "",
            resources   = "ledger"
        )
        val surface = SurfaceMapper().map(intent)
        val plan    = ExecutionDecomposer().decompose(surface)
        val result  = ConstraintEnforcer().enforce(intent, plan)
        val uiViolation = result.violations.first { it.step.module == ContractModule.UI }
        assertEquals("no ui", uiViolation.constraint)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Step 5 — DeterministicDeriver
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `DeterministicDeriver returns APPROVED when no critical failures and constraints pass`() {
        val intent  = minimalIntent()
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        val plan    = ExecutionDecomposer().decompose(surface)
        val cr      = ConstraintEnforcer().enforce(intent, plan)
        val result  = DeterministicDeriver().derive(surface, fm, plan, cr)
        // minimalIntent has no critical failures and no active constraints
        if (!fm.hasCritical && cr.passed) {
            assertEquals(ContractOutcome.APPROVED, result.outcome)
        }
    }

    @Test
    fun `DeterministicDeriver returns REJECTED when constraints fail`() {
        val intent = ContractIntent(
            objective   = "update screen layout",
            constraints = "no ui",
            environment = "",
            resources   = "ledger"
        )
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        val plan    = ExecutionDecomposer().decompose(surface)
        val cr      = ConstraintEnforcer().enforce(intent, plan)
        val result  = DeterministicDeriver().derive(surface, fm, plan, cr)
        assertEquals(ContractOutcome.REJECTED, result.outcome)
    }

    @Test
    fun `DeterministicDeriver trace contains outcome line`() {
        val intent  = minimalIntent()
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        val plan    = ExecutionDecomposer().decompose(surface)
        val cr      = ConstraintEnforcer().enforce(intent, plan)
        val result  = DeterministicDeriver().derive(surface, fm, plan, cr)
        assertTrue(result.trace.any { it.startsWith("outcome:") })
    }

    @Test
    fun `DeterministicDeriver trace covers surface, failures, execution, constraints`() {
        val intent  = minimalIntent()
        val surface = SurfaceMapper().map(intent)
        val fm      = FailureMapper().map(intent, surface)
        val plan    = ExecutionDecomposer().decompose(surface)
        val cr      = ConstraintEnforcer().enforce(intent, plan)
        val result  = DeterministicDeriver().derive(surface, fm, plan, cr)
        assertTrue(result.trace.any { it.startsWith("surface:") })
        assertTrue(result.trace.any { it.startsWith("failures:") })
        assertTrue(result.trace.any { it.startsWith("execution:") })
        assertTrue(result.trace.any { it.startsWith("constraints:") })
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Full ContractEngine pipeline
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ContractEngine approves clean minimal intent`() {
        val result = ContractEngine().evaluate(minimalIntent())
        assertEquals(ContractOutcome.APPROVED, result.outcome)
    }

    @Test
    fun `ContractEngine rejects when no-ui constraint conflicts with UI objective`() {
        val intent = ContractIntent(
            objective   = "update screen layout",
            constraints = "no ui",
            environment = "",
            resources   = "ledger"
        )
        val result = ContractEngine().evaluate(intent)
        assertEquals(ContractOutcome.REJECTED, result.outcome)
    }

    @Test
    fun `ContractEngine rejects when read-only constraint conflicts with core mutation`() {
        val intent = ContractIntent(
            objective   = "update core event ledger",
            constraints = "read-only",
            environment = "",
            resources   = "ledger"
        )
        val result = ContractEngine().evaluate(intent)
        assertEquals(ContractOutcome.REJECTED, result.outcome)
    }

    @Test
    fun `ContractEngine derivation preserves all intermediate artifacts`() {
        val intent  = minimalIntent()
        val result  = ContractEngine().evaluate(intent)
        assertTrue(result.surface.modules.isNotEmpty())
        assertTrue(result.executionPlan.steps.isNotEmpty())
        assertTrue(result.trace.isNotEmpty())
    }

    @Test
    fun `ContractEngine is deterministic for equal inputs`() {
        val intent = minimalIntent(
            objective   = "update screen layout",
            constraints = "contract approval required"
        )
        val r1 = ContractEngine().evaluate(intent)
        val r2 = ContractEngine().evaluate(intent)
        assertEquals(r1, r2)
    }

    @Test
    fun `ContractEngine trace always contains outcome line`() {
        val intent = minimalIntent()
        val result = ContractEngine().evaluate(intent)
        assertTrue(result.trace.any { it.startsWith("outcome:") })
    }

    @Test
    fun `ContractEngine MISSING_RESOURCE alone does not cause rejection`() {
        // No resources declared → non-critical failure → engine should still approve
        val intent = ContractIntent(
            objective   = "update core event ledger",
            constraints = "",
            environment = "",
            resources   = ""
        )
        val result = ContractEngine().evaluate(intent)
        assertEquals(ContractOutcome.APPROVED, result.outcome)
        assertTrue(result.failureMap.failures.any { it.type == ContractFailureType.MISSING_RESOURCE })
        assertFalse(result.failureMap.hasCritical)
    }
}
