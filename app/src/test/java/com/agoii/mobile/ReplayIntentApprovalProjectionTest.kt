package com.agoii.mobile

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.core.IntentSummary
import com.agoii.mobile.core.RiskSurface
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
                    Event(EventTypes.INTENT_PARTIAL_CREATED, mapOf("intentId" to "intent-1", "objective" to "build report", "completeness" to 0.2, "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard", "mobile"), "assumptions" to listOf("data source is available"), "uncertainties" to listOf("requested format is unspecified"), "missingInformation" to listOf("delivery destination"), "failureRisks" to listOf("report generation may fail if the source is empty"))),
                    Event(EventTypes.INTENT_IN_PROGRESS, mapOf("intentId" to "intent-1", "objective" to "build report", "completeness" to 1.0, "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard", "mobile"), "assumptions" to listOf("data source is available"), "uncertainties" to listOf("requested format is unspecified"), "missingInformation" to listOf("delivery destination"), "failureRisks" to listOf("report generation may fail if the source is empty"))),
                    Event(EventTypes.INTENT_COMPLETED, mapOf("intentId" to "intent-1", "objective" to "build report", "completeness" to 1.0, "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard", "mobile"), "assumptions" to listOf("data source is available"), "uncertainties" to listOf("requested format is unspecified"), "missingInformation" to listOf("delivery destination"), "failureRisks" to listOf("report generation may fail if the source is empty"))),
                    Event(EventTypes.INTENT_APPROVAL_REQUESTED, mapOf("intentId" to "intent-1", "objective" to "build report", "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard", "mobile"), "assumptions" to listOf("data source is available"), "uncertainties" to listOf("requested format is unspecified"), "missingInformation" to listOf("delivery destination"), "failureRisks" to listOf("report generation may fail if the source is empty")))
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
                keyConstraints = listOf("standard", "mobile"),
                riskSurface = RiskSurface(
                    assumptions = listOf("data source is available"),
                    uncertainties = listOf("requested format is unspecified"),
                    missingInformation = listOf("delivery destination"),
                    failureRisks = listOf("report generation may fail if the source is empty")
                )
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
                    Event(EventTypes.INTENT_APPROVAL_REQUESTED, mapOf("intentId" to "intent-1", "objective" to "build report", "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard"), "assumptions" to listOf("production data is accessible"), "uncertainties" to listOf("time range is ambiguous"), "missingInformation" to listOf("report audience"), "failureRisks" to listOf("access may be denied"))),
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
                keyConstraints = listOf("standard"),
                riskSurface = RiskSurface(
                    assumptions = listOf("production data is accessible"),
                    uncertainties = listOf("time range is ambiguous"),
                    missingInformation = listOf("report audience"),
                    failureRisks = listOf("access may be denied")
                )
            ),
            approvedState.governanceView.intentConstruction.summary
        )
        assertFalse(approvedState.governanceView.showIntentApprovalPanel)

        val rejectedState = Replay(
            MemoryRepository(
                listOf(
                    Event(EventTypes.INTENT_SUBMITTED, mapOf("intentId" to "intent-2", "objective" to "build report")),
                    Event(EventTypes.INTENT_APPROVAL_REQUESTED, mapOf("intentId" to "intent-2", "objective" to "build report", "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("mobile"), "assumptions" to listOf("mobile environment is configured"), "uncertainties" to listOf("target output is unclear"), "missingInformation" to listOf("deadline"), "failureRisks" to listOf("dependencies may be unavailable"))),
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
                keyConstraints = listOf("mobile"),
                riskSurface = RiskSurface(
                    assumptions = listOf("mobile environment is configured"),
                    uncertainties = listOf("target output is unclear"),
                    missingInformation = listOf("deadline"),
                    failureRisks = listOf("dependencies may be unavailable")
                )
            ),
            rejectedState.governanceView.intentConstruction.summary
        )
        assertFalse(rejectedState.governanceView.showIntentApprovalPanel)
    }

    @Test
    fun `replay defaults risk surface when payload omits risk keys`() {
        val state = Replay(
            MemoryRepository(
                listOf(
                    Event(EventTypes.INTENT_SUBMITTED, mapOf("intentId" to "intent-3", "objective" to "build report")),
                    Event(EventTypes.INTENT_APPROVAL_REQUESTED, mapOf("intentId" to "intent-3", "objective" to "build report", "interpretedMeaning" to "Build a report from the current project data", "keyConstraints" to listOf("standard")))
                )
            )
        ).replayStructuralState("pid")

        assertEquals(
            RiskSurface(
                assumptions = emptyList(),
                uncertainties = emptyList(),
                missingInformation = emptyList(),
                failureRisks = emptyList()
            ),
            state.governanceView.intentConstruction.summary.riskSurface
        )
    }
}
