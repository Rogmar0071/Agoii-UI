package com.agoii.mobile

import com.agoii.mobile.execution.ExecutionContract
import com.agoii.mobile.execution.adapter.NemoClawAdapter
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ══════════════════════════════════════════════════════════════════════════════
// AGOII–EXECUTION-ADAPTER-001 TESTS
// NemoClawAdapter Unit Tests
// ══════════════════════════════════════════════════════════════════════════════

class NemoClawAdapterTest {

    /**
     * Test: Adapter creates temp file with correct JSON structure.
     */
    @Test
    fun testContractSerialization() {
        // Create a simple echo script that returns the input contract as execution report
        val testScript = File.createTempFile("test_nemoclaw_", ".sh")
        testScript.writeText("""#!/bin/bash
            |# Read contract from file
            |CONTRACT_FILE="${'$'}1"
            |# Extract execution_id
            |EXEC_ID=$(grep -o '"execution_id":"[^"]*"' "${'$'}CONTRACT_FILE" | cut -d'"' -f4)
            |# Return success report with execution_id
            |echo "{\"execution_id\":\"${'$'}EXEC_ID\",\"status\":\"SUCCESS\",\"exit_code\":0,\"outputs\":[],\"artifact\":null}"
        """.trimMargin())
        testScript.setExecutable(true)

        try {
            val adapter = NemoClawAdapter(
                nemoClawExecutable = testScript.absolutePath,
                nemoClawScript     = "", // Not used when executable is a script
                defaultTimeoutMs   = 5000L
            )

            val contract = ExecutionContract(
                contractId      = "test-contract-001",
                name            = "Test Contract",
                position        = 1,
                reportReference = "RRID-001"
            )

            val report = adapter.execute(contract, timeoutMs = 5000L)

            assertNotNull(report, "Report should not be null")
            assertEquals("SUCCESS", report.status, "Report should indicate success")
            assertNotNull(report.executionId, "Execution ID should be present")
        } finally {
            testScript.delete()
        }
    }

    /**
     * Test: Adapter enforces timeout.
     */
    @Test
    fun testTimeout() {
        // Create a script that sleeps longer than the timeout
        val testScript = File.createTempFile("test_nemoclaw_timeout_", ".sh")
        testScript.writeText("""#!/bin/bash
            |sleep 10
            |echo '{"execution_id":"test","status":"SUCCESS"}'
        """.trimMargin())
        testScript.setExecutable(true)

        try {
            val adapter = NemoClawAdapter(
                nemoClawExecutable = testScript.absolutePath,
                nemoClawScript     = "",
                defaultTimeoutMs   = 1000L
            )

            val contract = ExecutionContract(
                contractId      = "test-contract-timeout",
                name            = "Test Timeout",
                position        = 1,
                reportReference = "RRID-TIMEOUT"
            )

            val report = adapter.execute(contract, timeoutMs = 1000L)

            assertNotNull(report, "Report should not be null")
            assertEquals("FAILURE", report.status, "Report should indicate failure due to timeout")
            assertTrue(
                report.failureSurface?.get("error_code")?.toString()?.contains("TIMEOUT") == true,
                "Failure should indicate timeout"
            )
        } finally {
            testScript.delete()
        }
    }

    /**
     * Test: Adapter handles process failure.
     */
    @Test
    fun testProcessFailure() {
        // Create a script that exits with error
        val testScript = File.createTempFile("test_nemoclaw_fail_", ".sh")
        testScript.writeText("""#!/bin/bash
            |exit 1
        """.trimMargin())
        testScript.setExecutable(true)

        try {
            val adapter = NemoClawAdapter(
                nemoClawExecutable = testScript.absolutePath,
                nemoClawScript     = "",
                defaultTimeoutMs   = 5000L
            )

            val contract = ExecutionContract(
                contractId      = "test-contract-fail",
                name            = "Test Failure",
                position        = 1,
                reportReference = "RRID-FAIL"
            )

            val report = adapter.execute(contract, timeoutMs = 5000L)

            assertNotNull(report, "Report should not be null")
            // Process exits without printing valid JSON, so we expect JSON parse failure
            assertEquals("FAILURE", report.status, "Report should indicate failure")
        } finally {
            testScript.delete()
        }
    }

    /**
     * Test: Adapter validates execution_id integrity.
     */
    @Test
    fun testExecutionIdMismatch() {
        // Create a script that returns a different execution_id
        val testScript = File.createTempFile("test_nemoclaw_id_", ".sh")
        testScript.writeText("""#!/bin/bash
            |echo '{"execution_id":"wrong-id","status":"SUCCESS","exit_code":0,"outputs":[],"artifact":null}'
        """.trimMargin())
        testScript.setExecutable(true)

        try {
            val adapter = NemoClawAdapter(
                nemoClawExecutable = testScript.absolutePath,
                nemoClawScript     = "",
                defaultTimeoutMs   = 5000L
            )

            val contract = ExecutionContract(
                contractId      = "test-contract-id",
                name            = "Test ID Mismatch",
                position        = 1,
                reportReference = "RRID-ID"
            )

            val report = adapter.execute(contract, timeoutMs = 5000L)

            assertNotNull(report, "Report should not be null")
            assertEquals("FAILURE", report.status, "Report should indicate failure due to ID mismatch")
            assertTrue(
                report.failureSurface?.get("error_code")?.toString()?.contains("EXECUTION_ID_MISMATCH") == true,
                "Failure should indicate execution ID mismatch"
            )
        } finally {
            testScript.delete()
        }
    }
}
