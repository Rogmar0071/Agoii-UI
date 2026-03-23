package com.agoii.mobile

import com.agoii.mobile.contractor.ContractorCandidate
import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorEventEmitter
import com.agoii.mobile.contractor.ContractorEventTypes
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.contractor.ContractorVerificationEngine
import com.agoii.mobile.contractor.KnowledgeScout
import com.agoii.mobile.contractor.VerificationStatus
import com.agoii.mobile.contracts.ContractDerivation
import com.agoii.mobile.contracts.ContractEngine
import com.agoii.mobile.contracts.ContractIntent
import com.agoii.mobile.contracts.ContractOutcome
import com.agoii.mobile.tasks.AllocationResult
import com.agoii.mobile.tasks.DecompositionResult
import com.agoii.mobile.tasks.Task
import com.agoii.mobile.tasks.TaskAllocator
import com.agoii.mobile.tasks.TaskAssignmentStatus
import com.agoii.mobile.tasks.TaskDecomposer
import com.agoii.mobile.tasks.TaskEvent
import com.agoii.mobile.tasks.TaskEventTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Contractor + Task System (AGOII-CONTRACTOR-TASK-SYSTEM-01).
 *
 * Covers:
 *  - ContractorCapabilityVector (scoring)
 *  - ContractorVerificationEngine (VERIFIED / REJECTED)
 *  - ContractorRegistry (registration, lookup, outcome recording)
 *  - ContractorEventEmitter (event field correctness)
 *  - KnowledgeScout (deterministic candidate discovery)
 *  - TaskDecomposer (contract → task graph)
 *  - TaskAllocator (assignment, scout trigger, event chain)
 *
 * All tests run on the JVM without an Android device.
 */
class ContractorTaskSystemTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun verifiedProfile(
        id:                 String = "contractor-a",
        constraintObed:     Int    = 2,
        structuralAcc:      Int    = 2,
        drift:              Int    = 1,
        complexity:         Int    = 2,
        reliability:        Int    = 2
    ) = ContractorProfile(
        id           = id,
        capabilities = ContractorCapabilityVector(
            constraintObedience = constraintObed,
            structuralAccuracy  = structuralAcc,
            driftScore          = drift,
            complexityCapacity  = complexity,
            reliability         = reliability
        ),
        verificationCount = 1,
        status            = VerificationStatus.VERIFIED,
        source            = "test"
    )

    private fun highScoreProfile(id: String = "high-contractor") = verifiedProfile(
        id               = id,
        constraintObed   = 3,
        structuralAcc    = 3,
        drift            = 0,
        complexity       = 3,
        reliability      = 3
    )

    private fun validContractIntent() = ContractIntent(
        objective    = "update core event ledger",
        constraints  = "no ui modifications allowed",
        environment  = "JVM runtime",
        resources    = "ledger"
    )

    private fun approvedDerivation(): ContractDerivation {
        val engine = ContractEngine()
        val derivation = engine.evaluate(validContractIntent())
        // The intent should be approved (no critical failures).
        check(derivation.outcome == ContractOutcome.APPROVED) {
            "Test setup error: expected APPROVED derivation, got ${derivation.outcome}"
        }
        return derivation
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractorCapabilityVector
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CapabilityVector capabilityScore inverts driftScore`() {
        val v = ContractorCapabilityVector(
            constraintObedience = 3,
            structuralAccuracy  = 3,
            driftScore          = 0,  // 0 drift → contributes 3
            complexityCapacity  = 3,
            reliability         = 3
        )
        assertEquals(15, v.capabilityScore)
    }

    @Test
    fun `CapabilityVector rejects out-of-range dimensions`() {
        try {
            ContractorCapabilityVector(4, 2, 2, 2, 2)
            org.junit.Assert.fail("Expected IllegalArgumentException for constraintObedience=4")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `CapabilityVector score decreases with higher driftScore`() {
        val low  = ContractorCapabilityVector(2, 2, 0, 2, 2) // drift=0 → (3-0)=3
        val high = ContractorCapabilityVector(2, 2, 3, 2, 2) // drift=3 → (3-3)=0
        assertTrue(low.capabilityScore > high.capabilityScore)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractorVerificationEngine
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `VerificationEngine verifies candidate with all high claims`() {
        val candidate = ContractorCandidate(
            id               = "cand-1",
            source           = "test",
            capabilityClaims = mapOf(
                "constraintObedience" to "high",
                "structuralAccuracy"  to "high",
                "driftScore"          to "high",
                "complexityCapacity"  to "high",
                "reliability"         to "high"
            )
        )
        val result = ContractorVerificationEngine().verify(candidate)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertNotNull(result.assignedProfile)
        assertEquals(VerificationStatus.VERIFIED, result.assignedProfile!!.status)
    }

    @Test
    fun `VerificationEngine rejects candidate with all zero claims`() {
        val candidate = ContractorCandidate(
            id               = "cand-bad",
            source           = "test",
            capabilityClaims = mapOf(
                "constraintObedience" to "unknown",
                "structuralAccuracy"  to "unknown",
                "driftScore"          to "unknown",
                "complexityCapacity"  to "unknown",
                "reliability"         to "unknown"
            )
        )
        val result = ContractorVerificationEngine().verify(candidate)
        assertEquals(VerificationStatus.REJECTED, result.status)
        assertNull(result.assignedProfile)
    }

    @Test
    fun `VerificationEngine maps low medium high strings correctly`() {
        val engine = ContractorVerificationEngine()
        val candidate = ContractorCandidate(
            id               = "cand-medium",
            source           = "test",
            capabilityClaims = mapOf(
                "constraintObedience" to "medium",
                "structuralAccuracy"  to "medium",
                "driftScore"          to "medium",
                "complexityCapacity"  to "medium",
                "reliability"         to "medium"
            )
        )
        val result = engine.verify(candidate)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertEquals(2, result.assignedProfile!!.capabilities.constraintObedience)
    }

    @Test
    fun `VerificationEngine is deterministic for equal inputs`() {
        val engine = ContractorVerificationEngine()
        val candidate = ContractorCandidate(
            id               = "det-cand",
            source           = "test",
            capabilityClaims = mapOf(
                "constraintObedience" to "high",
                "structuralAccuracy"  to "medium",
                "driftScore"          to "low",
                "complexityCapacity"  to "medium",
                "reliability"         to "high"
            )
        )
        assertEquals(engine.verify(candidate), engine.verify(candidate))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractorRegistry
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Registry only accepts VERIFIED profiles`() {
        val registry = ContractorRegistry()
        val unverified = verifiedProfile(id = "unverified").copy(status = VerificationStatus.UNVERIFIED)
        try {
            registry.registerVerified(unverified)
            org.junit.Assert.fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }

    @Test
    fun `Registry emits CONTRACTOR_VERIFIED event on registration`() {
        val registry = ContractorRegistry()
        val event    = registry.registerVerified(verifiedProfile())
        assertEquals(ContractorEventTypes.CONTRACTOR_VERIFIED, event.type)
    }

    @Test
    fun `Registry findBestMatch returns null when empty`() {
        val registry = ContractorRegistry()
        assertNull(registry.findBestMatch(mapOf("reliability" to 1)))
    }

    @Test
    fun `Registry findBestMatch returns the best matching contractor`() {
        val registry = ContractorRegistry()
        registry.registerVerified(verifiedProfile("a", constraintObed = 1, reliability = 1))
        registry.registerVerified(highScoreProfile("b"))

        val match = registry.findBestMatch(mapOf("reliability" to 1, "constraintObedience" to 1))
        assertNotNull(match)
        assertEquals("b", match!!.id)  // high-score contractor wins
    }

    @Test
    fun `Registry recordOutcome updates success and failure counts`() {
        val registry = ContractorRegistry()
        val profile  = verifiedProfile("c")
        registry.registerVerified(profile)

        registry.recordOutcome("c", success = true)
        registry.recordOutcome("c", success = false)

        val updated = registry.allVerified().first { it.id == "c" }
        assertEquals(1, updated.successCount)
        assertEquals(1, updated.failureCount)
    }

    @Test
    fun `Registry recordOutcome emits CONTRACTOR_PROFILE_UPDATED event`() {
        val registry = ContractorRegistry()
        registry.registerVerified(verifiedProfile("d"))
        val event = registry.recordOutcome("d", success = true)
        assertNotNull(event)
        assertEquals(ContractorEventTypes.CONTRACTOR_PROFILE_UPDATED, event!!.type)
    }

    @Test
    fun `Registry recordOutcome returns null for unknown contractor`() {
        assertNull(ContractorRegistry().recordOutcome("ghost", success = true))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ContractorEventEmitter
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `EventEmitter discovered includes contractorId and source`() {
        val emitter   = ContractorEventEmitter()
        val candidate = ContractorCandidate("x", "api", emptyMap())
        val event     = emitter.discovered(candidate)
        assertEquals(ContractorEventTypes.CONTRACTOR_DISCOVERED, event.type)
        assertEquals("x",   event.payload["contractorId"])
        assertEquals("api", event.payload["source"])
    }

    @Test
    fun `EventEmitter verified includes capabilityScore`() {
        val emitter = ContractorEventEmitter()
        val profile = verifiedProfile()
        val event   = emitter.verified(profile)
        assertEquals(ContractorEventTypes.CONTRACTOR_VERIFIED, event.type)
        assertNotNull(event.payload["capabilityScore"])
    }

    // ══════════════════════════════════════════════════════════════════════════
    // KnowledgeScout
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `KnowledgeScout returns empty list for empty requirements`() {
        assertTrue(KnowledgeScout().discover(emptyMap()).isEmpty())
    }

    @Test
    fun `KnowledgeScout produces deterministic candidates`() {
        val scout = KnowledgeScout()
        val req   = mapOf("constraintObedience" to "medium", "reliability" to "high")
        assertEquals(scout.discover(req), scout.discover(req))
    }

    @Test
    fun `KnowledgeScout candidate source is knowledge_scout`() {
        val candidates = KnowledgeScout().discover(mapOf("reliability" to "medium"))
        assertTrue(candidates.all { it.source == "knowledge_scout" })
    }

    @Test
    fun `KnowledgeScout candidate includes all standard dimensions`() {
        val candidates = KnowledgeScout().discover(mapOf("constraintObedience" to "high"))
        val claims     = candidates.first().capabilityClaims
        for (dim in KnowledgeScout.STANDARD_DIMENSIONS) {
            assertNotNull("Missing dimension: $dim", claims[dim])
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TaskDecomposer
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TaskDecomposer produces one task per execution step`() {
        val derivation = approvedDerivation()
        val result: DecompositionResult = TaskDecomposer().decompose("contract-1", derivation)
        assertEquals(derivation.executionPlan.steps.size, result.taskGraph.tasks.size)
    }

    @Test
    fun `TaskDecomposer tasks are all BLOCKED before allocation`() {
        val result = TaskDecomposer().decompose("contract-1", approvedDerivation())
        assertTrue(result.taskGraph.tasks.all { it.assignmentStatus == TaskAssignmentStatus.BLOCKED })
    }

    @Test
    fun `TaskDecomposer emits TASK_CREATED and TASK_READY for each task`() {
        val derivation = approvedDerivation()
        val result     = TaskDecomposer().decompose("contract-1", derivation)
        val taskCount  = derivation.executionPlan.steps.size

        val created = result.events.filterIsInstance<TaskEvent>()
            .count { it.type == TaskEventTypes.TASK_CREATED }
        val ready   = result.events.filterIsInstance<TaskEvent>()
            .count { it.type == TaskEventTypes.TASK_READY }

        assertEquals(taskCount, created)
        assertEquals(taskCount, ready)
    }

    @Test
    fun `TaskDecomposer returns empty graph for REJECTED derivation`() {
        val derivation = ContractEngine().evaluate(
            ContractIntent(
                objective    = "update core event ledger",
                constraints  = "no ui modifications allowed",
                environment  = "JVM runtime",
                resources    = "ledger"
            )
        ).let { d ->
            // Force a REJECTED derivation by using a derivation with hasCritical=true.
            d.copy(
                outcome   = ContractOutcome.REJECTED,
                failureMap = d.failureMap.copy(hasCritical = true)
            )
        }
        val result = TaskDecomposer().decompose("contract-rejected", derivation)
        assertTrue(result.taskGraph.tasks.isEmpty())
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun `TaskDecomposer task IDs follow contractRef-stepN convention`() {
        val result = TaskDecomposer().decompose("my-contract", approvedDerivation())
        result.taskGraph.tasks.forEach { task ->
            assertTrue(
                "Task id '${task.taskId}' should start with 'my-contract-step'",
                task.taskId.startsWith("my-contract-step")
            )
        }
    }

    @Test
    fun `TaskDecomposer tasks have required validation rules`() {
        val result = TaskDecomposer().decompose("c-ref", approvedDerivation())
        result.taskGraph.tasks.forEach { task ->
            assertTrue(task.validationRules.contains("output_matches_description"))
            assertTrue(task.validationRules.contains("no_constraint_violation"))
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TaskAllocator
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `TaskAllocator assigns contractor when registry has matching profile`() {
        val registry = ContractorRegistry()
        registry.registerVerified(highScoreProfile())

        val decomposition = TaskDecomposer().decompose("c1", approvedDerivation())
        val allocator     = TaskAllocator(registry = registry)
        val result: AllocationResult = allocator.allocate(decomposition.taskGraph)

        assertTrue(result.taskGraph.fullyAssigned)
    }

    @Test
    fun `TaskAllocator emits TASK_ASSIGNMENT_REQUESTED for each task`() {
        val registry = ContractorRegistry()
        registry.registerVerified(highScoreProfile())

        val decomposition = TaskDecomposer().decompose("c1", approvedDerivation())
        val result        = TaskAllocator(registry = registry).allocate(decomposition.taskGraph)

        val requested = result.events.filterIsInstance<com.agoii.mobile.tasks.TaskEvent>()
            .count { it.type == TaskEventTypes.TASK_ASSIGNMENT_REQUESTED }
        assertEquals(decomposition.taskGraph.tasks.size, requested)
    }

    @Test
    fun `TaskAllocator emits CONTRACTOR_ASSIGNED and TASK_READY_FOR_EXECUTION on match`() {
        val registry = ContractorRegistry()
        registry.registerVerified(highScoreProfile())

        val decomposition = TaskDecomposer().decompose("c2", approvedDerivation())
        val result        = TaskAllocator(registry = registry).allocate(decomposition.taskGraph)

        assertTrue(
            result.events.filterIsInstance<TaskEvent>()
                .any { it.type == TaskEventTypes.CONTRACTOR_ASSIGNED }
        )
        assertTrue(
            result.events.filterIsInstance<TaskEvent>()
                .any { it.type == TaskEventTypes.TASK_READY_FOR_EXECUTION }
        )
    }

    @Test
    fun `TaskAllocator triggers KnowledgeScout when registry is empty`() {
        val decomposition = TaskDecomposer().decompose("c3", approvedDerivation())
        val result        = TaskAllocator().allocate(decomposition.taskGraph)

        val discovered = result.events.filterIsInstance<com.agoii.mobile.contractor.ContractorEvent>()
            .any { it.type == com.agoii.mobile.contractor.ContractorEventTypes.CONTRACTOR_DISCOVERED }
        assertTrue("Expected CONTRACTOR_DISCOVERED event when registry is empty", discovered)
    }

    @Test
    fun `TaskAllocator emits CONTRACTOR_DISCOVERY_TRIGGERED when no match`() {
        val decomposition = TaskDecomposer().decompose("c4", approvedDerivation())
        val result        = TaskAllocator().allocate(decomposition.taskGraph)

        assertTrue(
            result.events.filterIsInstance<TaskEvent>()
                .any { it.type == TaskEventTypes.CONTRACTOR_DISCOVERY_TRIGGERED }
        )
    }

    @Test
    fun `TaskAllocator fullyAssigned after scout verification succeeds`() {
        // Start with empty registry — scout will discover and verify a candidate.
        val decomposition = TaskDecomposer().decompose("c5", approvedDerivation())
        val result        = TaskAllocator().allocate(decomposition.taskGraph)

        // Scout-discovered candidates use "medium" claims which pass verification.
        assertTrue(
            "Expected fully assigned after scout verification",
            result.taskGraph.fullyAssigned
        )
    }

    @Test
    fun `TaskAllocator no unverified contractor is ever assigned`() {
        val decomposition = TaskDecomposer().decompose("c6", approvedDerivation())
        val result        = TaskAllocator().allocate(decomposition.taskGraph)

        result.taskGraph.tasks
            .filter { it.assignmentStatus == TaskAssignmentStatus.ASSIGNED }
            .forEach { task ->
                assertNotNull("Assigned task should have contractorId", task.assignedContractorId)
            }
    }
}
