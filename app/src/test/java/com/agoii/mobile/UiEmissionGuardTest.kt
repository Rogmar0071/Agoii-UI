package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.LedgerValidationException
import com.agoii.mobile.ui.UiSendBridge
import com.agoii.mobile.ui.handleSend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AGOII-CT-UI-EMISSION-GUARD-01 — Consolidated UI Emission Guard Test Suite.
 *
 * Verifies:
 *  - No crash can originate from any send path
 *  - handleSend routes correctly based on ledger state
 *  - Illegal states produce user-visible feedback, not exceptions
 *  - LedgerValidationException and unchecked exceptions are fully contained
 *  - Unknown states are safely blocked
 *  - No crash under repeated calls (stress)
 */
class UiEmissionGuardTest {

    private val projectId = "test_project"

    private var lastMessage: String? = null
    private var reloadTriggered: Boolean = false

    private val showMessage: (String) -> Unit = { lastMessage = it }
    private val onReload:    () -> Unit        = { reloadTriggered = true }

    @Before
    fun reset() {
        lastMessage     = null
        reloadTriggered = false
    }

    // ── Fake bridge ───────────────────────────────────────────────────────────

    /**
     * In-memory [UiSendBridge] for JVM-only tests.
     *
     * Tracks each call so tests can assert what was (or was not) invoked.
     * Optionally throws a caller-supplied exception to exercise the try/catch guard.
     */
    private class FakeUiSendBridge(
        private val throwOnSubmit: Exception? = null,
        private val throwOnUpdate: Exception? = null
    ) : UiSendBridge {

        val calls = mutableListOf<String>()

        override fun submitIntent(projectId: String, objective: String): Boolean {
            throwOnSubmit?.let { throw it }
            calls.add("submit")
            return true
        }

        override fun updateIntent(projectId: String, objective: String): Boolean {
            throwOnUpdate?.let { throw it }
            calls.add("update")
            return true
        }
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private fun singleEvent(type: String): List<Event> =
        listOf(Event(type, emptyMap()))

    private fun eventsEndingWith(vararg types: String): List<Event> =
        types.map { Event(it, emptyMap()) }

    // ========================================================
    // TEST 1 — INITIAL SEND (NO CRASH)
    // ========================================================
    @Test
    fun test_initial_send_creates_intent() {
        val bridge = FakeUiSendBridge()

        handleSend(
            events      = emptyList(),
            input       = "Test objective",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertEquals(listOf("submit"), bridge.calls)
        assertNull("No error message expected", lastMessage)
        assertTrue("Reload must be triggered on success", reloadTriggered)
    }

    // ========================================================
    // TEST 2 — SECOND SEND → INTENT_UPDATED (NO CRASH)
    // ========================================================
    @Test
    fun test_intent_update_allowed_after_submitted() {
        val bridge = FakeUiSendBridge()
        val events = singleEvent(EventTypes.INTENT_SUBMITTED)

        handleSend(
            events      = events,
            input       = "Refined objective",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertEquals(listOf("update"), bridge.calls)
        assertNull("No error message expected", lastMessage)
        assertTrue("Reload must be triggered on success", reloadTriggered)
    }

    @Test
    fun test_intent_update_allowed_after_intent_updated() {
        val bridge = FakeUiSendBridge()
        val events = eventsEndingWith(EventTypes.INTENT_SUBMITTED, EventTypes.INTENT_UPDATED)

        handleSend(
            events      = events,
            input       = "Further refined objective",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertEquals(listOf("update"), bridge.calls)
        assertNull("No error message expected", lastMessage)
    }

    // ========================================================
    // TEST 3 — BLOCK AFTER FINALIZED (NO CRASH)
    // ========================================================
    @Test
    fun test_block_update_after_finalized() {
        val bridge = FakeUiSendBridge()
        val events = eventsEndingWith(EventTypes.INTENT_SUBMITTED, EventTypes.INTENT_FINALIZED)

        handleSend(
            events      = events,
            input       = "Should fail",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertTrue("Bridge must not be called in finalized state", bridge.calls.isEmpty())
        assertNotNull("User feedback must be shown", lastMessage)
        assertTrue(lastMessage!!.isNotEmpty())
    }

    // ========================================================
    // TEST 4 — BLOCK DURING EXECUTION (NO CRASH)
    // ========================================================
    @Test
    fun test_block_update_during_execution_authorized() {
        val bridge = FakeUiSendBridge()
        val events = singleEvent(EventTypes.EXECUTION_AUTHORIZED)

        handleSend(
            events      = events,
            input       = "Should fail",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertTrue("Bridge must not be called during execution", bridge.calls.isEmpty())
        assertNotNull("User feedback must be shown", lastMessage)
    }

    @Test
    fun test_block_update_during_execution_in_progress() {
        val bridge = FakeUiSendBridge()
        val events = singleEvent(EventTypes.EXECUTION_IN_PROGRESS)

        handleSend(
            events      = events,
            input       = "Should fail",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertTrue("Bridge must not be called during execution", bridge.calls.isEmpty())
        assertNotNull("User feedback must be shown", lastMessage)
    }

    @Test
    fun test_block_update_after_execution_aborted() {
        val bridge = FakeUiSendBridge()
        val events = singleEvent(EventTypes.EXECUTION_ABORTED)

        handleSend(
            events      = events,
            input       = "Should fail",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertTrue("Bridge must not be called in aborted state", bridge.calls.isEmpty())
        assertNotNull("User feedback must be shown", lastMessage)
    }

    // ========================================================
    // TEST 5 — LEDGER VALIDATION EXCEPTION CONTAINMENT
    // ========================================================
    @Test
    fun test_ledger_validation_exception_does_not_crash() {
        val bridge = FakeUiSendBridge(
            throwOnSubmit = LedgerValidationException("Duplicate submission")
        )

        handleSend(
            events      = emptyList(),
            input       = "Trigger",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertNotNull("Validation error must surface as user message", lastMessage)
        assertEquals("Duplicate submission", lastMessage)
    }

    @Test
    fun test_generic_exception_does_not_crash() {
        val bridge = FakeUiSendBridge(
            throwOnUpdate = RuntimeException("Unexpected failure")
        )
        val events = singleEvent(EventTypes.INTENT_SUBMITTED)

        handleSend(
            events      = events,
            input       = "Trigger",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertNotNull("Error must surface as user message, not crash", lastMessage)
    }

    // ========================================================
    // TEST 6 — UNKNOWN STATE SAFETY
    // ========================================================
    @Test
    fun test_unknown_state_safe_block() {
        val bridge = FakeUiSendBridge()
        val events = singleEvent("UNKNOWN_STATE_XYZ")

        handleSend(
            events      = events,
            input       = "Should block",
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertTrue("Bridge must not be called in unknown state", bridge.calls.isEmpty())
        assertNotNull("User feedback must be shown for unknown state", lastMessage)
    }

    @Test
    fun test_mid_execution_states_safe_block() {
        // States that belong to the execution pipeline but not intent-evolution
        // phase — the UI must not attempt to send in any of these states.
        val blockedStates = listOf(
            EventTypes.CONTRACTS_GENERATED,
            EventTypes.CONTRACTS_READY,
            EventTypes.CONTRACTS_APPROVED,
            EventTypes.EXECUTION_STARTED,
            EventTypes.EXECUTION_COMPLETED,
            EventTypes.ASSEMBLY_STARTED,
            EventTypes.ASSEMBLY_COMPLETED,
            EventTypes.ICS_COMPLETED,
            EventTypes.COMMIT_CONTRACT
        )

        for (state in blockedStates) {
            reset()
            val bridge = FakeUiSendBridge()

            handleSend(
                events      = singleEvent(state),
                input       = "Should block in $state",
                bridge      = bridge,
                projectId   = projectId,
                onReload    = onReload,
                showMessage = showMessage
            )

            assertTrue("Bridge must not be called when last event is $state", bridge.calls.isEmpty())
            assertNotNull("User feedback must be shown for state $state", lastMessage)
        }
    }

    // ========================================================
    // TEST 7 — NO CRASH GUARANTEE (STRESS LOOP)
    // ========================================================
    @Test
    fun test_no_crash_under_repeated_calls_empty_events() {
        val bridge = FakeUiSendBridge()

        // Repeated calls with empty event list — each routes to submitIntent
        repeat(50) { i ->
            handleSend(
                events      = emptyList(),
                input       = "Loop $i",
                bridge      = bridge,
                projectId   = projectId,
                onReload    = onReload,
                showMessage = showMessage
            )
        }

        assertEquals(50, bridge.calls.count { it == "submit" })
    }

    @Test
    fun test_no_crash_under_repeated_calls_intent_submitted() {
        val bridge = FakeUiSendBridge()
        val events = singleEvent(EventTypes.INTENT_SUBMITTED)

        // Repeated calls with intent-submitted state — each routes to updateIntent
        repeat(50) { i ->
            handleSend(
                events      = events,
                input       = "Refinement $i",
                bridge      = bridge,
                projectId   = projectId,
                onReload    = onReload,
                showMessage = showMessage
            )
        }

        assertEquals(50, bridge.calls.count { it == "update" })
    }

    // ========================================================
    // ADDITIONAL — EMPTY INPUT GUARD
    // ========================================================
    @Test
    fun test_empty_input_is_ignored() {
        val bridge = FakeUiSendBridge()

        handleSend(
            events      = emptyList(),
            input       = "   ",   // whitespace only
            bridge      = bridge,
            projectId   = projectId,
            onReload    = onReload,
            showMessage = showMessage
        )

        assertTrue("Bridge must not be called for empty input", bridge.calls.isEmpty())
        assertNull("No error message for empty input", lastMessage)
        assertFalse("Reload should not trigger for empty input", reloadTriggered)
    }
}
