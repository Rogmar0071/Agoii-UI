package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.IntentSummary
import com.agoii.mobile.core.Replay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayIntentApprovalProjectionTest {

    private class MemoryRepository(initial: List<Event>) : EventRepository {
        private val events = initial.toMutableList()

        override fun appendEvent(projectId: String, type: String, payload: Map<String, Any>) {
            events.add(Event(type, payload))
        }

        override fun loadEvents(projectId: String): List<Event> = events.toList()
    }

    @Test
    fun `replay exposes approval requested state from ledger`() {
        val replay = Replay(
            MemoryRepository(
                listOf(
                    Event(EventTypes.USER_MESSAGE_SUBMITTED, mapOf("text" to "build report")),
                    Event(EventTypes.INTENT_SUBMITTED, mapOf("intentId" to "intent-1", "objective" to "build report")),
                    Event(EventTypes.INTENT_PARTIAL_CREATED, mapOf("intentId" to "intent-1", "objective" to "build report", "completeness" to 0.2, "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard", "mobile"))),
                    Event(EventTypes.INTENT_IN_PROGRESS, mapOf("intentId" to "intent-1", "objective" to "build report", "completeness" to 1.0, "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard", "mobile"))),
                    Event(EventTypes.INTENT_COMPLETED, mapOf("intentId" to "intent-1", "objective" to "build report", "completeness" to 1.0, "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard", "mobile"))),
                    Event(EventTypes.INTENT_APPROVAL_REQUESTED, mapOf("intentId" to "intent-1", "objective" to "build report", "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard", "mobile")))
                )
            )
        )

        val state = replay.replayStructuralState("pid")

        assertTrue(state.governanceView.intentConstruction.approvalRequired)
        assertEquals("approval_requested", state.governanceView.intentConstruction.status)
        assertEquals("intent-1", state.governanceView.intentConstruction.intentId)
        assertEquals("build report", state.governanceView.intentConstruction.objective)
        assertEquals(
            IntentSummary(
                objective = "build report",
                interpretedMeaning = "Build a report from the current project data",
                keyConstraints = listOf("standard", "mobile")
            ),
            state.governanceView.intentConstruction.summary
        )
        assertTrue(state.governanceView.showIntentApprovalPanel)
    }

    @Test
    fun `replay exposes approved and rejected terminal approval states`() {
        val approvedState = Replay(
            MemoryRepository(
                listOf(
                    Event(EventTypes.INTENT_SUBMITTED, mapOf("intentId" to "intent-1", "objective" to "build report")),
                    Event(EventTypes.INTENT_APPROVAL_REQUESTED, mapOf("intentId" to "intent-1", "objective" to "build report", "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard"))),
                    Event(EventTypes.INTENT_APPROVED, mapOf("intentId" to "intent-1"))
                )
            )
        ).replayStructuralState("pid")

        assertEquals("approved", approvedState.governanceView.intentConstruction.status)
        assertTrue(approvedState.governanceView.intentConstruction.isApproved)
        assertEquals(
            IntentSummary(
                objective = "build report",
                interpretedMeaning = "Build a report from the current project data",
                keyConstraints = listOf("standard")
            ),
            approvedState.governanceView.intentConstruction.summary
        )
        assertFalse(approvedState.governanceView.showIntentApprovalPanel)

        val rejectedState = Replay(
            MemoryRepository(
                listOf(
                    Event(EventTypes.INTENT_SUBMITTED, mapOf("intentId" to "intent-2", "objective" to "build report")),
                    Event(EventTypes.INTENT_APPROVAL_REQUESTED, mapOf("intentId" to "intent-2", "objective" to "build report", "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("mobile"))),
                    Event(EventTypes.INTENT_REJECTED, mapOf("intentId" to "intent-2"))
                )
            )
        ).replayStructuralState("pid")

        assertEquals("rejected", rejectedState.governanceView.intentConstruction.status)
        assertFalse(rejectedState.governanceView.intentConstruction.isApproved)
        assertEquals(
            IntentSummary(
                objective = "build report",
                interpretedMeaning = "Build a report from the current project data",
                keyConstraints = listOf("mobile")
            ),
            rejectedState.governanceView.intentConstruction.summary
        )
        assertFalse(rejectedState.governanceView.showIntentApprovalPanel)
    }
}
