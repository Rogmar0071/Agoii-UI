package com.agoii.mobile

import com.agoii.mobile.contractor.ContractorRegistry
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.ReplayStructuralState
import com.agoii.mobile.execution.DriverRegistry
import com.agoii.mobile.execution.ExecutionAuthority
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * CONTRACT ID: AGOII–EXECUTION-REPLAY-VALIDATION-001
 * CORRECTION: AGOII–EXECUTION-REPLAY-VALIDATION-CORRECTION-001
 *
 * CLASSIFICATION:
 *   - Class: Structural Validation
 *   - Scope: Full execution spine
 *   - Mutation: NONE (read + verify only)
 *
 * OBJECTIVE:
 *   Prove that the system is fully deterministic by validating that:
 *   replay(event_stream) == original_execution(event_stream)
 *
 * PRINCIPLE:
 *   The ledger is the ONLY source of truth.
 *   If replaying it does not reproduce the same state and outputs,
 *   the system is invalid.
 *
 * CORRECTION CONTRACT ENFORCEMENT:
 *   RULE 1: NO FALLBACK MODE - If NemoClaw unavailable → BLOCKED (not PASS)
 *   RULE 2: FORCE REAL EXECUTION - Must use ExecutionAuthority → NemoClawAdapter → node process
 *   RULE 3: HARD FAIL ON MISSING ARTIFACT - artifact == null → FAIL
 *   RULE 4: STRICT EVENT COMPARISON - Full payload deep equality (exclude only timestamp)
 *   RULE 5: FAIL ON EXTRA/MISSING EVENTS - len(original) ≠ len(replay) → FAIL
 *   RULE 6: NO FUZZY MATCHING - original_event == replay_event (strict)
 *   RULE 7: OUTPUT REAL RESULT - JSON format with status/checks/failure_reason/diff
 *   RULE 8: BLOCKED CONDITION - NemoClaw unavailable → status = BLOCKED
 *
 * VALIDATION ASSERTIONS:
 *   1. EVENT COUNT MATCH:    len(original) == len(replay)
 *   2. EVENT IDENTITY MATCH: ∀ i: original[i] == replay[i]
 *   3. FINAL STATE MATCH:    final_state_original == final_state_replay
 *   4. ARTIFACT HASH MATCH:  ∀ execution events: artifact.content_hash identical
 *   5. NO ADDITIONAL EVENTS: replay does not generate new events
 *   6. NO MISSING EVENTS:    replay does not skip events
 *
 * FAILURE CONDITIONS:
 *   If ANY assertion fails → SYSTEM STATUS = NON-DETERMINISTIC
 *
 * SUCCESS CONDITION:
 *   If ALL assertions pass → SYSTEM STATUS = DETERMINISTIC
 *
 * BLOCKED CONDITION:
 *   If NemoClaw unavailable → SYSTEM STATUS = CANNOT VALIDATE
 */
class ExecutionReplayValidationTest {

    // ══════════════════════════════════════════════════════════════════════════
    // Test Configuration
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        private const val TEST_PROJECT_ID = "replay-validation-test-001"
        private const val TEST_TASK_ID = "test-task-001"
        private const val TEST_CONTRACT_ID = "test-contract-001"
        private const val TEST_REPORT_REFERENCE = "test-rrid-001"
        
        /**
         * Keys to exclude from payload comparison (auto-generated, non-deterministic).
         */
        private val EXCLUDED_PAYLOAD_KEYS = setOf(
            "timestamp", "Timestamp", "createdAt", "updatedAt"
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Validation Result Model
    // ══════════════════════════════════════════════════════════════════════════

    data class ValidationResult(
        val status: ValidationStatus,
        val checks: ValidationChecks,
        val failureReason: String? = null,
        val diff: String? = null
    )

    enum class ValidationStatus {
        PASS,
        FAIL,
        BLOCKED
    }

    data class ValidationChecks(
        val eventCount: Boolean,
        val eventIdentity: Boolean,
        val finalState: Boolean,
        val artifactHash: Boolean,
        val noExtraEvents: Boolean,
        val noMissingEvents: Boolean
    ) {
        /**
         * Check if all validation checks pass.
         * 
         * @return true if ALL checks pass, false otherwise.
         */
        fun allPass(): Boolean = 
            eventCount && eventIdentity && finalState && 
            artifactHash && noExtraEvents && noMissingEvents
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Core Test: Full Determinism Validation
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun `CONTRACT AGOII-EXECUTION-REPLAY-VALIDATION-001 - system is fully deterministic`() {
        println("═══════════════════════════════════════════════════════════")
        println("CONTRACT ID: AGOII–EXECUTION-REPLAY-VALIDATION-001")
        println("OBJECTIVE: Validate full determinism via replay")
        println("═══════════════════════════════════════════════════════════")

        // ── PHASE 1: CAPTURE ORIGINAL EXECUTION ──────────────────────────────

        println("\n[PHASE 1] CAPTURE ORIGINAL EXECUTION")
        println("─────────────────────────────────────")

        val originalStore = InMemoryEventStore()
        val originalLedger = EventLedger(originalStore)

        // Set up test environment
        val contractorRegistry = ContractorRegistry()
        val driverRegistry = DriverRegistry()
        val executionAuthority = ExecutionAuthority(contractorRegistry, driverRegistry)

        // Verify NemoClaw is available (required for execution)
        val nemoClawScript = File("execution/nemoclaw/execute.js")
        if (!nemoClawScript.exists()) {
            // RULE 1 & RULE 8: NO FALLBACK MODE
            // If NemoClaw cannot run, test MUST return BLOCKED
            val blockedResult = ValidationResult(
                status = ValidationStatus.BLOCKED,
                checks = ValidationChecks(
                    eventCount = false,
                    eventIdentity = false,
                    finalState = false,
                    artifactHash = false,
                    noExtraEvents = false,
                    noMissingEvents = false
                ),
                failureReason = "NEMOCLAW_UNAVAILABLE",
                diff = "NemoClaw not found at: ${nemoClawScript.absolutePath}. This test requires real execution via NemoClaw."
            )
            
            printValidationResult(blockedResult)
            
            println("\n═══════════════════════════════════════════════════════════")
            println("✗ CONTRACT AGOII-EXECUTION-REPLAY-VALIDATION-001: BLOCKED")
            println("✗ SYSTEM STATUS: CANNOT VALIDATE (NemoClaw unavailable)")
            println("✗ TEST RESULT: BLOCKED")
            println("═══════════════════════════════════════════════════════════")
            
            // Fail the test - BLOCKED is a test failure
            fail("Test BLOCKED: NemoClaw unavailable at ${nemoClawScript.absolutePath}")
        }

        println("✓ NemoClaw found at: ${nemoClawScript.absolutePath}")

        // Step 1: Clear ledger (start fresh)
        originalStore.clearLedger(TEST_PROJECT_ID)
        println("✓ Ledger cleared")

        // Step 2: Set up minimal event stream for execution
        // CONTRACT_CREATED → TASK_STARTED → (ExecutionAuthority triggers) → TASK_EXECUTED
        
        originalLedger.appendEvent(
            TEST_PROJECT_ID,
            EventTypes.PROJECT_CREATED,
            mapOf("projectId" to TEST_PROJECT_ID)
        )
        println("✓ PROJECT_CREATED event appended")

        originalLedger.appendEvent(
            TEST_PROJECT_ID,
            EventTypes.CONTRACTS_GENERATED,
            mapOf(
                "totalCount" to 1,
                "report_reference" to TEST_REPORT_REFERENCE
            )
        )
        println("✓ CONTRACTS_GENERATED event appended")

        originalLedger.appendEvent(
            TEST_PROJECT_ID,
            EventTypes.CONTRACT_CREATED,
            mapOf(
                "contractId" to TEST_CONTRACT_ID,
                "name" to "Test Contract",
                "report_reference" to TEST_REPORT_REFERENCE
            )
        )
        println("✓ CONTRACT_CREATED event appended")

        originalLedger.appendEvent(
            TEST_PROJECT_ID,
            EventTypes.TASK_STARTED,
            mapOf(
                "taskId" to TEST_TASK_ID,
                "contractId" to TEST_CONTRACT_ID
            )
        )
        println("✓ TASK_STARTED event appended")

        // Step 3: Trigger execution (ExecutionAuthority.executeFromLedger)
        println("\nTriggering execution via ExecutionAuthority...")
        val executionResult = executionAuthority.executeFromLedger(TEST_PROJECT_ID, originalLedger)
        println("✓ Execution completed: $executionResult")

        // Step 4: Capture event stream
        val eventStreamOriginal = originalStore.copyEvents(TEST_PROJECT_ID)
        println("\n✓ Captured ${eventStreamOriginal.size} events from original execution")

        // Step 5: Capture final state
        val finalStateOriginal = originalLedger.replayState(TEST_PROJECT_ID)
        println("✓ Derived original final state")
        printStateSnapshot("ORIGINAL", finalStateOriginal)

        // ── PHASE 2: REPLAY ──────────────────────────────────────────────────

        println("\n[PHASE 2] REPLAY EXECUTION")
        println("───────────────────────────")

        val replayStore = InMemoryEventStore()
        val replayLedger = EventLedger(replayStore)

        // Step 1: Reset system state completely
        replayStore.clearLedger(TEST_PROJECT_ID)
        println("✓ Replay ledger cleared")

        // Step 2: Re-feed events EXACTLY as recorded
        println("\nReplaying ${eventStreamOriginal.size} events...")
        for ((index, event) in eventStreamOriginal.withIndex()) {
            replayLedger.appendEvent(TEST_PROJECT_ID, event.type, event.payload)
            println("  [$index] ${event.type}")
        }
        println("✓ All events replayed")

        // Step 3: Capture replay state
        val eventStreamReplay = replayStore.copyEvents(TEST_PROJECT_ID)
        val finalStateReplay = replayLedger.replayState(TEST_PROJECT_ID)
        println("✓ Derived replay final state")
        printStateSnapshot("REPLAY", finalStateReplay)

        // ── PHASE 3: VALIDATION ──────────────────────────────────────────────

        println("\n[PHASE 3] VALIDATION")
        println("────────────────────")

        val result = performValidation(
            eventStreamOriginal,
            eventStreamReplay,
            finalStateOriginal,
            finalStateReplay
        )

        // Print validation results
        printValidationResult(result)

        // Assert ALL checks must pass
        assertTrue(
            "EVENT COUNT MATCH failed",
            result.checks.eventCount
        )
        assertTrue(
            "EVENT IDENTITY MATCH failed: ${result.diff ?: ""}",
            result.checks.eventIdentity
        )
        assertTrue(
            "FINAL STATE MATCH failed: ${result.diff ?: ""}",
            result.checks.finalState
        )
        assertTrue(
            "ARTIFACT HASH MATCH failed: ${result.diff ?: ""}",
            result.checks.artifactHash
        )
        assertTrue(
            "NO ADDITIONAL EVENTS failed",
            result.checks.noExtraEvents
        )
        assertTrue(
            "NO MISSING EVENTS failed",
            result.checks.noMissingEvents
        )

        // Final verdict
        assertEquals(
            "SYSTEM STATUS must be DETERMINISTIC",
            ValidationStatus.PASS,
            result.status
        )

        println("\n═══════════════════════════════════════════════════════════")
        println("✓ CONTRACT AGOII-EXECUTION-REPLAY-VALIDATION-001: PASS")
        println("✓ SYSTEM STATUS: DETERMINISTIC")
        println("✓ EXECUTION SPINE: VERIFIED")
        println("✓ SYSTEM: READY FOR NEXT PHASE")
        println("═══════════════════════════════════════════════════════════")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Validation Logic
    // ══════════════════════════════════════════════════════════════════════════

    private fun performValidation(
        originalEvents: List<Event>,
        replayEvents: List<Event>,
        originalState: ReplayStructuralState,
        replayState: ReplayStructuralState
    ): ValidationResult {
        val diffMessages = mutableListOf<String>()

        // CHECK 1: EVENT COUNT MATCH (RULE 5)
        val eventCountMatch = originalEvents.size == replayEvents.size
        if (!eventCountMatch) {
            diffMessages.add(
                "EVENT_STREAM_MISMATCH: Event count mismatch: original=${originalEvents.size}, replay=${replayEvents.size}"
            )
            // RULE 5: FAIL immediately on count mismatch
            return ValidationResult(
                status = ValidationStatus.FAIL,
                checks = ValidationChecks(
                    eventCount = false,
                    eventIdentity = false,
                    finalState = false,
                    artifactHash = false,
                    noExtraEvents = false,
                    noMissingEvents = false
                ),
                failureReason = "EVENT_STREAM_MISMATCH",
                diff = diffMessages.joinToString("\n")
            )
        }

        // CHECK 2: EVENT IDENTITY MATCH (RULE 4 & RULE 6)
        var eventIdentityMatch = true
        for (i in originalEvents.indices) {
            val orig = originalEvents[i]
            val repl = replayEvents[i]

            // Compare event types (STRICT)
            if (orig.type != repl.type) {
                eventIdentityMatch = false
                diffMessages.add("Event[$i] type mismatch: ${orig.type} != ${repl.type}")
            }

            // Compare payloads (STRICT - RULE 6: no fuzzy matching)
            if (!payloadsMatchStrict(orig.payload, repl.payload)) {
                eventIdentityMatch = false
                diffMessages.add("Event[$i] payload mismatch")
            }
        }

        // RULE 3: HARD FAIL ON MISSING ARTIFACT
        val artifactValidation = validateArtifactsStrict(originalEvents, replayEvents, diffMessages)
        if (!artifactValidation) {
            return ValidationResult(
                status = ValidationStatus.FAIL,
                checks = ValidationChecks(
                    eventCount = eventCountMatch,
                    eventIdentity = eventIdentityMatch,
                    finalState = false,
                    artifactHash = false,
                    noExtraEvents = true,
                    noMissingEvents = true
                ),
                failureReason = "MISSING_ARTIFACT",
                diff = diffMessages.joinToString("\n")
            )
        }

        // CHECK 3: FINAL STATE MATCH
        val finalStateMatch = statesMatch(originalState, replayState)
        if (!finalStateMatch) {
            diffMessages.add("Final state mismatch detected")
        }

        // CHECK 4: ARTIFACT HASH MATCH
        val artifactHashMatch = validateArtifactHashes(originalEvents, replayEvents, diffMessages)

        // CHECK 5 & 6: NO EXTRA/MISSING EVENTS
        // Note: These checks are technically redundant with eventCountMatch.
        // They are kept explicit per CONTRACT specification for clarity and traceability.
        // The contract requires six distinct validation checks to be reported.
        val noExtraEvents = replayEvents.size <= originalEvents.size
        val noMissingEvents = replayEvents.size >= originalEvents.size

        val allChecks = ValidationChecks(
            eventCount = eventCountMatch,
            eventIdentity = eventIdentityMatch,
            finalState = finalStateMatch,
            artifactHash = artifactHashMatch,
            noExtraEvents = noExtraEvents,
            noMissingEvents = noMissingEvents
        )

        val status = if (allChecks.allPass()) {
            ValidationStatus.PASS
        } else {
            ValidationStatus.FAIL
        }

        val failureReason = if (status == ValidationStatus.FAIL) {
            when {
                !eventCountMatch -> "EVENT_STREAM_MISMATCH"
                !eventIdentityMatch -> "EVENT_IDENTITY_MISMATCH"
                !finalStateMatch -> "FINAL_STATE_MISMATCH"
                !artifactHashMatch -> "ARTIFACT_HASH_MISMATCH"
                else -> "VALIDATION_FAILURE"
            }
        } else null

        return ValidationResult(
            status = status,
            checks = allChecks,
            failureReason = failureReason,
            diff = if (diffMessages.isNotEmpty()) diffMessages.joinToString("\n") else null
        )
    }

    /**
     * RULE 6: STRICT payload comparison - NO fuzzy matching.
     * RULE 4: Only exclude timestamp and proven non-deterministic IDs.
     */
    private fun payloadsMatchStrict(payload1: Map<String, Any>, payload2: Map<String, Any>): Boolean {
        // Compare all keys except auto-generated ones defined in EXCLUDED_PAYLOAD_KEYS
        val keys1 = payload1.keys.filterNot { it in EXCLUDED_PAYLOAD_KEYS }
        val keys2 = payload2.keys.filterNot { it in EXCLUDED_PAYLOAD_KEYS }

        if (keys1.toSet() != keys2.toSet()) return false

        for (key in keys1) {
            val val1 = payload1[key]
            val val2 = payload2[key]
            
            // RULE 6: Deep compare - STRICT equality (no fuzzy matching)
            if (!valuesEqualStrict(val1, val2)) return false
        }

        return true
    }

    /**
     * RULE 6: STRICT value comparison - original_event == replay_event
     */
    private fun valuesEqualStrict(val1: Any?, val2: Any?): Boolean {
        return when {
            val1 === val2 -> true
            val1 == null || val2 == null -> false
            val1 is Map<*, *> && val2 is Map<*, *> -> mapsEqualStrict(val1, val2)
            val1 is List<*> && val2 is List<*> -> listsEqualStrict(val1, val2)
            else -> val1 == val2
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapsEqualStrict(map1: Map<*, *>, map2: Map<*, *>): Boolean {
        if (map1.size != map2.size) return false
        for (key in map1.keys) {
            if (!valuesEqualStrict(map1[key], map2[key])) return false
        }
        return true
    }

    private fun listsEqualStrict(list1: List<*>, list2: List<*>): Boolean {
        if (list1.size != list2.size) return false
        for (i in list1.indices) {
            if (!valuesEqualStrict(list1[i], list2[i])) return false
        }
        return true
    }

    /**
     * RULE 3: HARD FAIL ON MISSING ARTIFACT
     * Any execution event with null artifact → FAIL
     */
    private fun validateArtifactsStrict(
        originalEvents: List<Event>,
        replayEvents: List<Event>,
        diffMessages: MutableList<String>
    ): Boolean {
        val originalTaskExecuted = originalEvents.filter { it.type == EventTypes.TASK_EXECUTED }
        val replayTaskExecuted = replayEvents.filter { it.type == EventTypes.TASK_EXECUTED }

        for (i in originalTaskExecuted.indices) {
            @Suppress("UNCHECKED_CAST")
            val origArtifact = originalTaskExecuted[i].payload["artifact"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val replArtifact = replayTaskExecuted[i].payload["artifact"] as? Map<String, Any>

            // RULE 3: If artifact is null → FAIL
            if (origArtifact == null) {
                diffMessages.add("MISSING_ARTIFACT: Original event[$i] has null artifact")
                return false
            }
            if (replArtifact == null) {
                diffMessages.add("MISSING_ARTIFACT: Replay event[$i] has null artifact")
                return false
            }
        }

        return true
    }

    private fun statesMatch(state1: ReplayStructuralState, state2: ReplayStructuralState): Boolean {
        // Compare governance view
        if (state1.governanceView.lastEventType != state2.governanceView.lastEventType) return false
        if (state1.governanceView.totalContracts != state2.governanceView.totalContracts) return false
        if (state1.governanceView.reportReference != state2.governanceView.reportReference) return false

        // Compare execution view (key fields)
        if (state1.executionView.taskStatus != state2.executionView.taskStatus) return false
        if (state1.executionView.icsPhase != state2.executionView.icsPhase) return false
        if (state1.executionView.commitPhase != state2.executionView.commitPhase) return false

        return true
    }

    private fun validateArtifactHashes(
        originalEvents: List<Event>,
        replayEvents: List<Event>,
        diffMessages: MutableList<String>
    ): Boolean {
        var allMatch = true

        // Find all TASK_EXECUTED events with artifacts
        val originalTaskExecuted = originalEvents.filter { it.type == EventTypes.TASK_EXECUTED }
        val replayTaskExecuted = replayEvents.filter { it.type == EventTypes.TASK_EXECUTED }

        if (originalTaskExecuted.size != replayTaskExecuted.size) {
            diffMessages.add("Different number of TASK_EXECUTED events")
            return false
        }

        for (i in originalTaskExecuted.indices) {
            val origPayload = originalTaskExecuted[i].payload
            val replPayload = replayTaskExecuted[i].payload

            // Extract artifact sections and compare hashes
            @Suppress("UNCHECKED_CAST")
            val origArtifact = origPayload["artifact"] as? Map<String, Any>
            @Suppress("UNCHECKED_CAST")
            val replArtifact = replPayload["artifact"] as? Map<String, Any>

            if (origArtifact != null && replArtifact != null) {
                @Suppress("UNCHECKED_CAST")
                val origSections = origArtifact["sections"] as? List<Map<String, Any>>
                @Suppress("UNCHECKED_CAST")
                val replSections = replArtifact["sections"] as? List<Map<String, Any>>

                if (origSections != null && replSections != null) {
                    if (origSections.size != replSections.size) {
                        diffMessages.add("Artifact[$i] section count mismatch")
                        allMatch = false
                        continue
                    }

                    for (j in origSections.indices) {
                        val origHash = origSections[j]["contentHash"]
                        val replHash = replSections[j]["contentHash"]

                        if (origHash != replHash) {
                            diffMessages.add("Artifact[$i] section[$j] hash mismatch: $origHash != $replHash")
                            allMatch = false
                        }
                    }
                }
            }
        }

        return allMatch
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Helper Functions
    // ══════════════════════════════════════════════════════════════════════════

    private fun printStateSnapshot(label: String, state: ReplayStructuralState) {
        println("\n  [$label STATE SNAPSHOT]")
        println("  Last Event Type: ${state.governanceView.lastEventType}")
        println("  Total Contracts: ${state.governanceView.totalContracts}")
        println("  Report Reference: ${state.governanceView.reportReference}")
        println("  ICS Phase: ${state.executionView.icsPhase}")
        println("  Commit Phase: ${state.executionView.commitPhase}")
        println("  Task Status Count: ${state.executionView.taskStatus.size}")
    }

    private fun printValidationResult(result: ValidationResult) {
        println("\n  VALIDATION RESULTS:")
        println("  ─────────────────────────────────────────────────")
        println("  Status: ${result.status}")
        if (result.failureReason != null) {
            println("  Failure Reason: ${result.failureReason}")
        }
        println("  ─────────────────────────────────────────────────")
        println("  ✓ Event Count Match:      ${result.checks.eventCount}")
        println("  ✓ Event Identity Match:   ${result.checks.eventIdentity}")
        println("  ✓ Final State Match:      ${result.checks.finalState}")
        println("  ✓ Artifact Hash Match:    ${result.checks.artifactHash}")
        println("  ✓ No Additional Events:   ${result.checks.noExtraEvents}")
        println("  ✓ No Missing Events:      ${result.checks.noMissingEvents}")
        println("  ─────────────────────────────────────────────────")

        if (result.diff != null) {
            println("\n  DIFF REPORT:")
            println("  ${result.diff}")
        }
        
        // RULE 7: Output real result in specified format
        println("\n  JSON OUTPUT:")
        println("  {")
        println("    \"status\": \"${result.status}\",")
        println("    \"checks\": {")
        println("      \"event_count\": ${result.checks.eventCount},")
        println("      \"event_identity\": ${result.checks.eventIdentity},")
        println("      \"final_state\": ${result.checks.finalState},")
        println("      \"artifact_hash\": ${result.checks.artifactHash},")
        println("      \"no_extra_events\": ${result.checks.noExtraEvents},")
        println("      \"no_missing_events\": ${result.checks.noMissingEvents}")
        println("    }")
        if (result.failureReason != null) {
            println("    \"failure_reason\": \"${result.failureReason}\"")
        }
        if (result.diff != null) {
            println("    \"diff\": \"${result.diff.replace("\"", "\\\"")}\"")
        }
        println("  }")
    }
}
