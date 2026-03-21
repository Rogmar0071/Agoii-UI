package com.agoii.mobile

import com.agoii.mobile.irs.GapReport
import com.agoii.mobile.irs.IntentResolutionSystem
import com.agoii.mobile.irs.IrsFailureState
import com.agoii.mobile.irs.IrsResult
import com.agoii.mobile.irs.RawIntent
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for IRS-01 — Intent Resolution System.
 *
 * All tests run on the JVM (no Android framework required).
 *
 * Verifies:
 *  - Reconstruction engine extracts explicit fields only.
 *  - Gap detection engine identifies all missing required fields.
 *  - Swarm validation catches contradictions across three passes.
 *  - Simulation validation rejects placeholder values.
 *  - PCCV gate only passes when all dimensions are true.
 *  - Full pipeline certifies a valid structured intent.
 *  - Full pipeline rejects every invalid input with the correct failure state.
 */
class IrsTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** A fully-structured valid intent string. */
    private fun validIntent(
        objective: String          = "Build the authentication module",
        successCriteria: String    = "All unit tests pass and coverage exceeds 80%",
        constraints: String        = "Must use Kotlin, no third-party auth libraries",
        environment: String        = "Android API 26+, JVM 17",
        resources: String          = "Android SDK, Kotlin stdlib, JUnit 4",
        acceptanceBoundary: String = "APK builds successfully and all tests green"
    ): String = """
        objective: $objective
        success_criteria: $successCriteria
        constraints: $constraints
        environment: $environment
        resources: $resources
        acceptance_boundary: $acceptanceBoundary
    """.trimIndent()

    // ── Empty / blank input ───────────────────────────────────────────────────

    @Test
    fun `process rejects empty string with INTENT_INCOMPLETE`() {
        val result = IntentResolutionSystem.process("")
        assertTrue(result is IrsResult.Rejected)
        val r = result as IrsResult.Rejected
        assertEquals(IrsFailureState.INTENT_INCOMPLETE, r.failureState)
        assertEquals(IntentResolutionSystem.REQUIRED_FIELDS, r.missingFields)
    }

    @Test
    fun `process rejects blank string with INTENT_INCOMPLETE`() {
        val result = IntentResolutionSystem.process("   \n   ")
        assertTrue(result is IrsResult.Rejected)
        assertEquals(IrsFailureState.INTENT_INCOMPLETE, (result as IrsResult.Rejected).failureState)
    }

    // ── Reconstruction engine ─────────────────────────────────────────────────

    @Test
    fun `reconstruct extracts all six fields from structured input`() {
        val raw   = RawIntent(validIntent())
        val draft = IntentResolutionSystem.reconstruct(raw)
        assertEquals("Build the authentication module", draft.objective)
        assertEquals("All unit tests pass and coverage exceeds 80%", draft.successCriteria)
        assertEquals("Must use Kotlin, no third-party auth libraries", draft.constraints)
        assertEquals("Android API 26+, JVM 17", draft.environment)
        assertEquals("Android SDK, Kotlin stdlib, JUnit 4", draft.resources)
        assertEquals("APK builds successfully and all tests green", draft.acceptanceBoundary)
    }

    @Test
    fun `reconstruct accepts field aliases`() {
        val input = """
            goal: Implement feature X
            success: All integration tests pass
            limitations: No external network calls
            platform: Android API 28
            tools: Android SDK
            acceptance: CI pipeline is green
        """.trimIndent()
        val draft = IntentResolutionSystem.reconstruct(RawIntent(input))
        assertNotNull("goal alias should map to objective", draft.objective)
        assertNotNull("success alias should map to success_criteria", draft.successCriteria)
        assertNotNull("limitations alias should map to constraints", draft.constraints)
        assertNotNull("platform alias should map to environment", draft.environment)
        assertNotNull("tools alias should map to resources", draft.resources)
        assertNotNull("acceptance alias should map to acceptance_boundary", draft.acceptanceBoundary)
    }

    @Test
    fun `reconstruct does not auto-fill missing fields`() {
        val input = "objective: Do something important"
        val draft = IntentResolutionSystem.reconstruct(RawIntent(input))
        assertEquals("Do something important", draft.objective)
        assertNull("IRS must not fill success_criteria",    draft.successCriteria)
        assertNull("IRS must not fill constraints",         draft.constraints)
        assertNull("IRS must not fill environment",         draft.environment)
        assertNull("IRS must not fill resources",           draft.resources)
        assertNull("IRS must not fill acceptance_boundary", draft.acceptanceBoundary)
    }

    @Test
    fun `reconstruct first occurrence wins for duplicate keys`() {
        val input = """
            objective: First objective
            objective: Second objective
        """.trimIndent()
        val draft = IntentResolutionSystem.reconstruct(RawIntent(input))
        assertEquals("First objective", draft.objective)
    }

    @Test
    fun `reconstruct ignores lines without a colon`() {
        val input = "just a sentence without a colon"
        val draft = IntentResolutionSystem.reconstruct(RawIntent(input))
        assertNull(draft.objective)
    }

    // ── Gap detection engine ──────────────────────────────────────────────────

    @Test
    fun `detectGaps returns no missing fields for complete draft`() {
        val draft = IntentResolutionSystem.reconstruct(RawIntent(validIntent()))
        val gap   = IntentResolutionSystem.detectGaps(draft)
        assertTrue(gap.isComplete)
        assertTrue(gap.missingFields.isEmpty())
    }

    @Test
    fun `detectGaps reports all six fields missing for empty draft`() {
        val draft = IntentResolutionSystem.reconstruct(RawIntent("no fields here"))
        val gap   = IntentResolutionSystem.detectGaps(draft)
        assertFalse(gap.isComplete)
        assertEquals(6, gap.missingFields.size)
        assertTrue(gap.missingFields.containsAll(IntentResolutionSystem.REQUIRED_FIELDS))
    }

    @Test
    fun `detectGaps reports specific missing fields`() {
        val input = """
            objective: Build something
            constraints: No external deps
            environment: Android 26+
            resources: Kotlin SDK
            acceptance_boundary: Tests pass
        """.trimIndent()
        val draft = IntentResolutionSystem.reconstruct(RawIntent(input))
        val gap   = IntentResolutionSystem.detectGaps(draft)
        assertFalse(gap.isComplete)
        assertEquals(listOf("success_criteria"), gap.missingFields)
    }

    @Test
    fun `detectGaps treats field shorter than MIN_FIELD_LENGTH as missing`() {
        val input = "objective: ok\nsuccess_criteria: All tests pass\nconstraints: None\nenvironment: Android\nresources: SDK\nacceptance_boundary: Done"
        val draft = IntentResolutionSystem.reconstruct(RawIntent(input))
        val gap   = IntentResolutionSystem.detectGaps(draft)
        // "ok" is 2 chars < MIN_FIELD_LENGTH (3), so objective is missing
        assertTrue(gap.missingFields.contains("objective"))
    }

    // ── Swarm validation ──────────────────────────────────────────────────────

    @Test
    fun `swarm validation passes for distinct non-contradicting fields`() {
        val draft  = IntentResolutionSystem.reconstruct(RawIntent(validIntent()))
        val result = IntentResolutionSystem.runSwarmValidation(draft)
        assertTrue(result.passed)
    }

    @Test
    fun `swarm validation fails when objective equals success_criteria`() {
        val identical = "Build the feature"
        val input = """
            objective: $identical
            success_criteria: $identical
            constraints: No external deps
            environment: Android
            resources: SDK
            acceptance_boundary: Feature is deployed
        """.trimIndent()
        val draft  = IntentResolutionSystem.reconstruct(RawIntent(input))
        val result = IntentResolutionSystem.runSwarmValidation(draft)
        assertFalse(result.passed)
        assertTrue(result.detail.contains("Pass 1"))
    }

    @Test
    fun `swarm validation fails when constraints equals resources`() {
        val same = "Use Kotlin"
        val input = """
            objective: Build the feature
            success_criteria: All tests pass
            constraints: $same
            environment: Android
            resources: $same
            acceptance_boundary: Deployed
        """.trimIndent()
        val draft  = IntentResolutionSystem.reconstruct(RawIntent(input))
        val result = IntentResolutionSystem.runSwarmValidation(draft)
        assertFalse(result.passed)
        assertTrue(result.detail.contains("Pass 2"))
    }

    @Test
    fun `swarm validation fails when acceptance_boundary equals objective`() {
        val same = "Build the feature"
        val input = """
            objective: $same
            success_criteria: All tests pass
            constraints: No external deps
            environment: Android
            resources: SDK
            acceptance_boundary: $same
        """.trimIndent()
        val draft  = IntentResolutionSystem.reconstruct(RawIntent(input))
        val result = IntentResolutionSystem.runSwarmValidation(draft)
        assertFalse(result.passed)
        assertTrue(result.detail.contains("Pass 3"))
    }

    // ── Simulation validation ─────────────────────────────────────────────────

    @Test
    fun `simulation validation passes for substantive fields`() {
        val draft  = IntentResolutionSystem.reconstruct(RawIntent(validIntent()))
        val result = IntentResolutionSystem.runSimulationValidation(draft)
        assertTrue(result.passed)
    }

    @Test
    fun `simulation validation rejects placeholder tbd`() {
        val input = """
            objective: Build something
            success_criteria: tbd
            constraints: No external deps
            environment: Android
            resources: SDK
            acceptance_boundary: Done
        """.trimIndent()
        val draft  = IntentResolutionSystem.reconstruct(RawIntent(input))
        val result = IntentResolutionSystem.runSimulationValidation(draft)
        assertFalse(result.passed)
        assertTrue(result.detail.contains("success_criteria"))
    }

    @Test
    fun `simulation validation rejects placeholder none`() {
        val input = """
            objective: Build something
            success_criteria: All tests pass
            constraints: none
            environment: Android
            resources: SDK
            acceptance_boundary: Done
        """.trimIndent()
        val draft  = IntentResolutionSystem.reconstruct(RawIntent(input))
        val result = IntentResolutionSystem.runSimulationValidation(draft)
        assertFalse(result.passed)
        assertTrue(result.detail.contains("constraints"))
    }

    // ── PCCV gate ─────────────────────────────────────────────────────────────

    @Test
    fun `PCCV report all-pass for fully valid draft`() {
        val draft       = IntentResolutionSystem.reconstruct(RawIntent(validIntent()))
        val gapReport   = IntentResolutionSystem.detectGaps(draft)
        val swarmResult = IntentResolutionSystem.runSwarmValidation(draft)
        val simResult   = IntentResolutionSystem.runSimulationValidation(draft)
        val pccv        = IntentResolutionSystem.buildPccvReport(gapReport, swarmResult, simResult)

        assertTrue(pccv.completeness)
        assertTrue(pccv.consistency)
        assertTrue(pccv.feasibility)
        assertTrue(pccv.nonAssumption)
        assertTrue(pccv.reproducibility)
        assertTrue(pccv.allPass)
    }

    @Test
    fun `PCCV completeness fails when gap report is incomplete`() {
        val gapReport   = GapReport(missingFields = listOf("success_criteria"))
        val swarmResult = IntentResolutionSystem.SwarmResult(true, "ok")
        val simResult   = IntentResolutionSystem.SimulationResult(true, "ok")
        val pccv        = IntentResolutionSystem.buildPccvReport(gapReport, swarmResult, simResult)

        assertFalse(pccv.completeness)
        assertFalse(pccv.allPass)
    }

    // ── Full pipeline integration ─────────────────────────────────────────────

    @Test
    fun `process certifies a fully structured valid intent`() {
        val result = IntentResolutionSystem.process(validIntent())
        assertTrue("Expected Certified but got: $result", result is IrsResult.Certified)
        val certified = (result as IrsResult.Certified).intent
        assertEquals("CERTIFIED", certified.validationStatus)
        assertEquals("Build the authentication module", certified.objective)
        assertEquals("All unit tests pass and coverage exceeds 80%", certified.successCriteria)
        assertEquals("Must use Kotlin, no third-party auth libraries", certified.constraints)
        assertEquals("Android API 26+, JVM 17", certified.environment)
        assertEquals("Android SDK, Kotlin stdlib, JUnit 4", certified.resources)
        assertEquals("APK builds successfully and all tests green", certified.acceptanceBoundary)
        assertTrue(certified.pccvReport.allPass)
        assertEquals(6, certified.evidenceRefs.size)
        certified.evidenceRefs.forEach { ref ->
            assertEquals("raw_input", ref.source)
        }
        assertNotNull(certified.intentId)
        assertTrue(certified.intentId.isNotBlank())
    }

    @Test
    fun `process rejects intent with missing success_criteria`() {
        val input = """
            objective: Build the feature
            constraints: No external deps
            environment: Android 26+
            resources: Kotlin SDK
            acceptance_boundary: CI is green
        """.trimIndent()
        val result = IntentResolutionSystem.process(input)
        assertTrue(result is IrsResult.Rejected)
        val r = result as IrsResult.Rejected
        assertEquals(IrsFailureState.INTENT_INCOMPLETE, r.failureState)
        assertTrue(r.missingFields.contains("success_criteria"))
    }

    @Test
    fun `process rejects intent with swarm contradiction (INTENT_UNSTABLE)`() {
        val same = "Build the feature"
        val result = IntentResolutionSystem.process(
            validIntent(objective = same, acceptanceBoundary = same)
        )
        assertTrue(result is IrsResult.Rejected)
        assertEquals(IrsFailureState.INTENT_UNSTABLE, (result as IrsResult.Rejected).failureState)
    }

    @Test
    fun `process rejects intent with placeholder value (INTENT_INFEASIBLE)`() {
        val result = IntentResolutionSystem.process(
            validIntent(successCriteria = "tbd")
        )
        assertTrue(result is IrsResult.Rejected)
        assertEquals(IrsFailureState.INTENT_INFEASIBLE, (result as IrsResult.Rejected).failureState)
    }

    @Test
    fun `certified intent has non-empty intentId`() {
        val result = IntentResolutionSystem.process(validIntent())
        assertTrue(result is IrsResult.Certified)
        val id = (result as IrsResult.Certified).intent.intentId
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `each process call produces a unique intentId`() {
        val r1 = IntentResolutionSystem.process(validIntent()) as IrsResult.Certified
        val r2 = IntentResolutionSystem.process(validIntent()) as IrsResult.Certified
        assertNotEquals(r1.intent.intentId, r2.intent.intentId)
    }
}
