package com.agoii.mobile

import com.agoii.mobile.execution.Artifact
import com.agoii.mobile.execution.ArtifactSection
import com.agoii.mobile.execution.ContractorExecutor
import com.agoii.mobile.execution.DriverRegistry
import com.agoii.mobile.execution.ExecutionReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AGOII-ARTIFACT-SPINE-001.
 *
 * Covers:
 *  - Artifact construction (buildArtifact)
 *  - SHA-256 hash determinism
 *  - ExecutionReport structure
 *  - Artifact section ordering
 *
 * CONTRACT: AGOII-ARTIFACT-SPINE-001 — Artifact Spine Insertion
 */
class ArtifactSpineTest {

    private lateinit var executor: ContractorExecutor

    @Before
    fun setUp() {
        val driverRegistry = DriverRegistry()
        executor = ContractorExecutor(driverRegistry)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Artifact Construction
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `buildArtifact - creates sections with correct IDs`() {
        val output = listOf("content_1", "content_2", "content_3")
        val artifact = executor.buildArtifact("exec_123", output)
        
        assertEquals("exec_123", artifact.executionId)
        assertEquals(3, artifact.sections.size)
        assertEquals("section_0", artifact.sections[0].sectionId)
        assertEquals("section_1", artifact.sections[1].sectionId)
        assertEquals("section_2", artifact.sections[2].sectionId)
    }

    @Test
    fun `buildArtifact - sections contain correct content`() {
        val output = listOf("Hello", "World")
        val artifact = executor.buildArtifact("exec_456", output)
        
        assertEquals("Hello", artifact.sections[0].content)
        assertEquals("World", artifact.sections[1].content)
    }

    @Test
    fun `buildArtifact - generates SHA-256 hashes`() {
        val output = listOf("test content")
        val artifact = executor.buildArtifact("exec_789", output)
        
        // SHA-256 of "test content" should be deterministic and 64 hex chars
        val hash = artifact.sections[0].contentHash
        assertNotNull(hash)
        assertEquals(64, hash.length)  // SHA-256 produces 32 bytes = 64 hex chars
        
        // Verify determinism: same input produces same hash
        val artifact2 = executor.buildArtifact("exec_789", output)
        assertEquals(hash, artifact2.sections[0].contentHash)
    }

    @Test
    fun `buildArtifact - handles empty output list`() {
        val artifact = executor.buildArtifact("exec_empty", emptyList())
        
        assertEquals("exec_empty", artifact.executionId)
        assertTrue(artifact.sections.isEmpty())
    }

    @Test
    fun `buildArtifact - sections are ordered`() {
        val output = listOf("first", "second", "third", "fourth")
        val artifact = executor.buildArtifact("exec_order", output)
        
        // Verify ordering is preserved
        assertEquals("section_0", artifact.sections[0].sectionId)
        assertEquals("section_1", artifact.sections[1].sectionId)
        assertEquals("section_2", artifact.sections[2].sectionId)
        assertEquals("section_3", artifact.sections[3].sectionId)
        
        assertEquals("first", artifact.sections[0].content)
        assertEquals("second", artifact.sections[1].content)
        assertEquals("third", artifact.sections[2].content)
        assertEquals("fourth", artifact.sections[3].content)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Hash Determinism
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `SHA-256 hash - identical content produces identical hash`() {
        val content = "deterministic test content"
        val artifact1 = executor.buildArtifact("exec_1", listOf(content))
        val artifact2 = executor.buildArtifact("exec_2", listOf(content))
        
        // Different executionIds but same content should have same hash
        assertEquals(artifact1.sections[0].contentHash, artifact2.sections[0].contentHash)
    }

    @Test
    fun `SHA-256 hash - different content produces different hash`() {
        val artifact1 = executor.buildArtifact("exec_1", listOf("content A"))
        val artifact2 = executor.buildArtifact("exec_2", listOf("content B"))
        
        // Different content should have different hashes
        assertTrue(artifact1.sections[0].contentHash != artifact2.sections[0].contentHash)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // ExecutionReport Structure
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `ExecutionReport - can be constructed with artifact`() {
        val artifact = Artifact(
            executionId = "exec_123",
            sections = listOf(
                ArtifactSection("section_0", "content", "hash123")
            )
        )
        
        val report = ExecutionReport(
            executionId = "exec_123",
            status = "SUCCESS",
            exitCode = 0,
            outputs = listOf("output1"),
            artifact = artifact,
            failureSurface = null
        )
        
        assertNotNull(report.artifact)
        assertEquals("exec_123", report.artifact?.executionId)
        assertEquals(1, report.artifact?.sections?.size)
    }

    @Test
    fun `ExecutionReport - artifact is nullable`() {
        val report = ExecutionReport(
            executionId = "exec_failure",
            status = "FAILURE",
            exitCode = 1,
            outputs = emptyList(),
            artifact = null,
            failureSurface = mapOf("error" to "execution failed")
        )
        
        assertEquals("FAILURE", report.status)
        assertEquals(null, report.artifact)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Artifact Invariants
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `Artifact invariants - sectionId is stable`() {
        val output = listOf("A", "B", "C")
        val artifact1 = executor.buildArtifact("exec_1", output)
        val artifact2 = executor.buildArtifact("exec_2", output)
        
        // Section IDs should be deterministic based on index
        assertEquals("section_0", artifact1.sections[0].sectionId)
        assertEquals("section_0", artifact2.sections[0].sectionId)
        assertEquals("section_1", artifact1.sections[1].sectionId)
        assertEquals("section_1", artifact2.sections[1].sectionId)
    }

    @Test
    fun `Artifact invariants - contentHash is deterministic`() {
        val content = "stable content for hashing"
        val artifact1 = executor.buildArtifact("exec_A", listOf(content))
        val artifact2 = executor.buildArtifact("exec_B", listOf(content))
        
        // Same content must produce same hash (determinism)
        assertEquals(artifact1.sections[0].contentHash, artifact2.sections[0].contentHash)
    }
}
