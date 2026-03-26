package com.agoii.mobile

import com.agoii.mobile.contractors.Capability
import com.agoii.mobile.contractors.ContractRequirement
import com.agoii.mobile.contractors.ContractorProfile
import com.agoii.mobile.contractors.ContractorRegistry
import com.agoii.mobile.contractors.DeterministicMatchingEngine
import com.agoii.mobile.contractors.RejectedContractor
import com.agoii.mobile.contractors.ResolutionResult
import com.agoii.mobile.contractors.SwarmCompositionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for CONTRACTORS_MODULE_V1.
 *
 * Covers:
 *  - ContractorProfile validation
 *  - ContractorRegistry (all, findByCapability, get)
 *  - DeterministicMatchingEngine: Matched, Swarm, Blocked paths
 *  - SwarmCompositionEngine: minimal greedy cover, partial-cover blocked
 *  - Determinism invariant: equal inputs → equal outputs
 *  - Audit trace completeness
 */
class ContractorsModuleTest {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun profile(
        id: String,
        capabilities: Set<Capability>,
        reliability: Double = 0.9,
        drift: Double = 0.1,
        latency: Double = 1.0
    ) = ContractorProfile(
        contractorId = id,
        capabilities = capabilities,
        reliability  = reliability,
        drift        = drift,
        latency      = latency
    )

    private fun requirement(
        caps: Set<Capability>,
        forbidden: Set<Capability> = emptySet(),
        maxDrift: Double = 0.3,
        minRel: Double = 0.5,
        allowSwarm: Boolean = true
    ) = ContractRequirement(
        contractId             = "contract-test",
        requiredCapabilities   = caps,
        forbiddenCapabilities  = forbidden,
        maxDriftTolerance      = maxDrift,
        minReliability         = minRel,
        allowSwarm             = allowSwarm
    )

    // ─── ContractorProfile ────────────────────────────────────────────────────

    @Test
    fun `ContractorProfile rejects reliability out of range`() {
        try {
            profile("x", emptySet(), reliability = 1.5)
            org.junit.Assert.fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `ContractorProfile rejects drift out of range`() {
        try {
            profile("x", emptySet(), drift = -0.1)
            org.junit.Assert.fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `ContractorProfile accepts boundary values`() {
        val p = profile("x", emptySet(), reliability = 0.0, drift = 1.0)
        assertEquals(0.0, p.reliability, 0.0)
        assertEquals(1.0, p.drift,       0.0)
    }

    // ─── ContractorRegistry ───────────────────────────────────────────────────

    @Test
    fun `Registry all returns every contractor`() {
        val a = profile("a", setOf(Capability.TESTING))
        val b = profile("b", setOf(Capability.CODE_GENERATION))
        val registry = ContractorRegistry(listOf(a, b))
        assertEquals(2, registry.all().size)
    }

    @Test
    fun `Registry findByCapability filters correctly`() {
        val a = profile("a", setOf(Capability.TESTING))
        val b = profile("b", setOf(Capability.CODE_GENERATION))
        val registry = ContractorRegistry(listOf(a, b))
        val found = registry.findByCapability(Capability.TESTING)
        assertEquals(1, found.size)
        assertEquals("a", found[0].contractorId)
    }

    @Test
    fun `Registry get returns profile by id`() {
        val a = profile("a", setOf(Capability.TESTING))
        val registry = ContractorRegistry(listOf(a))
        assertNotNull(registry.get("a"))
        assertNull(registry.get("unknown"))
    }

    // ─── DeterministicMatchingEngine — Blocked paths ──────────────────────────

    @Test
    fun `Matching returns Blocked when registry is empty`() {
        val engine = DeterministicMatchingEngine(ContractorRegistry(emptyList()))
        val result = engine.resolve(requirement(setOf(Capability.TESTING)))
        assertTrue(result is ResolutionResult.Blocked)
    }

    @Test
    fun `Matching returns Blocked when all contractors have forbidden capability`() {
        val a = profile("a", setOf(Capability.TESTING, Capability.UI_GENERATION))
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a)))
        val req    = requirement(
            caps     = setOf(Capability.TESTING),
            forbidden = setOf(Capability.UI_GENERATION)
        )
        val result = engine.resolve(req)
        assertTrue(result is ResolutionResult.Blocked)
        val trace = (result as ResolutionResult.Blocked).trace
        assertNotNull(trace)
        assertEquals(1, trace!!.rejected.size)
        assertTrue(trace.evaluated.isEmpty())
    }

    @Test
    fun `Matching returns Blocked when reliability too low`() {
        val a = profile("a", setOf(Capability.TESTING), reliability = 0.3)
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING), minRel = 0.5))
        assertTrue(result is ResolutionResult.Blocked)
    }

    @Test
    fun `Matching returns Blocked when drift too high`() {
        val a = profile("a", setOf(Capability.TESTING), drift = 0.8)
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING), maxDrift = 0.3))
        assertTrue(result is ResolutionResult.Blocked)
    }

    @Test
    fun `Matching returns Blocked when swarm disabled and no full-coverage contractor`() {
        val a = profile("a", setOf(Capability.TESTING))
        val b = profile("b", setOf(Capability.CODE_GENERATION))
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a, b)))
        val req    = requirement(
            caps       = setOf(Capability.TESTING, Capability.CODE_GENERATION),
            allowSwarm = false
        )
        val result = engine.resolve(req)
        assertTrue(result is ResolutionResult.Blocked)
    }

    // ─── DeterministicMatchingEngine — Matched path ───────────────────────────

    @Test
    fun `Matching returns Matched when single contractor covers all requirements`() {
        val a = profile("a", setOf(Capability.TESTING, Capability.CODE_GENERATION))
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING, Capability.CODE_GENERATION)))
        assertTrue(result is ResolutionResult.Matched)
        val matched = result as ResolutionResult.Matched
        assertEquals("a", matched.contractor.contractorId)
    }

    @Test
    fun `Matching selects highest-scored contractor deterministically`() {
        val low  = profile("low",  setOf(Capability.TESTING), reliability = 0.6, drift = 0.2)
        val high = profile("high", setOf(Capability.TESTING), reliability = 0.95, drift = 0.05)
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(low, high)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING)))
        assertTrue(result is ResolutionResult.Matched)
        assertEquals("high", (result as ResolutionResult.Matched).contractor.contractorId)
    }

    @Test
    fun `Matching is deterministic — equal inputs produce equal outputs`() {
        val a = profile("a", setOf(Capability.TESTING), reliability = 0.8, drift = 0.1)
        val b = profile("b", setOf(Capability.TESTING), reliability = 0.8, drift = 0.1)
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a, b)))
        val req    = requirement(setOf(Capability.TESTING))
        val r1     = engine.resolve(req)
        val r2     = engine.resolve(req)
        assertTrue(r1 is ResolutionResult.Matched)
        assertTrue(r2 is ResolutionResult.Matched)
        assertEquals(
            (r1 as ResolutionResult.Matched).contractor.contractorId,
            (r2 as ResolutionResult.Matched).contractor.contractorId
        )
    }

    @Test
    fun `Matched result includes complete audit trace`() {
        val a = profile("a", setOf(Capability.TESTING))
        val b = profile("b", setOf(Capability.CODE_GENERATION), reliability = 0.3) // rejected
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a, b)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING), minRel = 0.5))
        assertTrue(result is ResolutionResult.Matched)
        val trace = (result as ResolutionResult.Matched).trace
        assertEquals(1, trace.evaluated.size)
        assertEquals(1, trace.rejected.size)
        assertEquals("a", trace.selectedId)
        assertNull(trace.swarmIds)
    }

    // ─── DeterministicMatchingEngine — Swarm path ────────────────────────────

    @Test
    fun `Matching returns Swarm when no single contractor covers all required capabilities`() {
        val a = profile("a", setOf(Capability.TESTING))
        val b = profile("b", setOf(Capability.CODE_GENERATION))
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a, b)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING, Capability.CODE_GENERATION)))
        assertTrue(result is ResolutionResult.Swarm)
        val swarm = (result as ResolutionResult.Swarm).contractors
        assertEquals(2, swarm.size)
        val ids = swarm.map { it.contractorId }.toSet()
        assertTrue("a" in ids)
        assertTrue("b" in ids)
    }

    @Test
    fun `Swarm trace lists swarmIds`() {
        val a = profile("a", setOf(Capability.TESTING))
        val b = profile("b", setOf(Capability.CODE_GENERATION))
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a, b)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING, Capability.CODE_GENERATION)))
        assertTrue(result is ResolutionResult.Swarm)
        val trace = (result as ResolutionResult.Swarm).trace
        assertNotNull(trace.swarmIds)
        assertEquals(2, trace.swarmIds!!.size)
        assertNull(trace.selectedId)
    }

    @Test
    fun `Matching returns Blocked when swarm cannot cover all required capabilities`() {
        val a = profile("a", setOf(Capability.TESTING))
        // CODE_GENERATION is required but no contractor has it
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING, Capability.CODE_GENERATION)))
        assertTrue(result is ResolutionResult.Blocked)
    }

    // ─── SwarmCompositionEngine — unit tests ──────────────────────────────────

    @Test
    fun `SwarmEngine composes minimal set covering all capabilities`() {
        val a    = profile("a", setOf(Capability.TESTING, Capability.REFACTORING))
        val b    = profile("b", setOf(Capability.CODE_GENERATION))
        val c    = profile("c", setOf(Capability.TESTING))   // redundant
        val req  = requirement(setOf(Capability.TESTING, Capability.CODE_GENERATION, Capability.REFACTORING))
        val swarmEngine = SwarmCompositionEngine()
        val scored: List<com.agoii.mobile.contractors.ContractorScore> = emptyList()
        val rejected: List<RejectedContractor> = emptyList()
        val result = swarmEngine.compose(req, listOf(a, b, c), scored, rejected)
        assertTrue(result is ResolutionResult.Swarm)
        // 'a' covers TESTING+REFACTORING, 'b' covers CODE_GENERATION → minimal swarm = {a,b}
        val swarm = (result as ResolutionResult.Swarm).contractors
        val ids   = swarm.map { it.contractorId }.toSet()
        assertTrue("a" in ids)
        assertTrue("b" in ids)
        assertTrue("c" !in ids)
    }

    @Test
    fun `SwarmEngine returns Blocked when pool cannot cover all capabilities`() {
        val a   = profile("a", setOf(Capability.TESTING))
        val req = requirement(setOf(Capability.TESTING, Capability.INFRASTRUCTURE))
        val swarmEngine = SwarmCompositionEngine()
        val result = swarmEngine.compose(req, listOf(a), emptyList(), emptyList())
        assertTrue(result is ResolutionResult.Blocked)
    }

    // ─── Score formula verification ───────────────────────────────────────────

    @Test
    fun `Full-coverage contractor scores capabilityScore of 1_0`() {
        val a = profile("a", setOf(Capability.TESTING, Capability.CODE_GENERATION),
            reliability = 0.9, drift = 0.0)
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(a)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING, Capability.CODE_GENERATION)))
        assertTrue(result is ResolutionResult.Matched)
        val score = (result as ResolutionResult.Matched).score
        assertEquals(1.0, score.capabilityScore, 0.001)
    }

    @Test
    fun `Higher reliability produces higher finalScore with equal capability and drift`() {
        val low  = profile("low",  setOf(Capability.TESTING), reliability = 0.6, drift = 0.1)
        val high = profile("high", setOf(Capability.TESTING), reliability = 0.9, drift = 0.1)
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(low, high)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING)))
        assertTrue(result is ResolutionResult.Matched)
        assertEquals("high", (result as ResolutionResult.Matched).contractor.contractorId)
    }

    @Test
    fun `Lower drift produces higher finalScore with equal capability and reliability`() {
        val highDrift = profile("hd", setOf(Capability.TESTING), reliability = 0.8, drift = 0.25)
        val lowDrift  = profile("ld", setOf(Capability.TESTING), reliability = 0.8, drift = 0.05)
        val engine = DeterministicMatchingEngine(ContractorRegistry(listOf(highDrift, lowDrift)))
        val result = engine.resolve(requirement(setOf(Capability.TESTING)))
        assertTrue(result is ResolutionResult.Matched)
        assertEquals("ld", (result as ResolutionResult.Matched).contractor.contractorId)
    }
}
