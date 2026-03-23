package com.agoii.mobile.ui.core

import com.agoii.mobile.core.Event

/**
 * Immutable block in the rendered timeline.
 *
 * @property type     The event type string (from [com.agoii.mobile.core.EventTypes]).
 * @property status   A presentable status label derived from the event's type.
 * @property position Zero-based position of this block in the ordered timeline.
 */
data class TimelineBlock(
    val type: String,
    val status: String,
    val position: Int
)

/**
 * Maps an ordered list of [Event]s into an ordered list of [TimelineBlock]s.
 *
 * Contract:
 * - Event ordering is immutable: blocks are always produced in ledger order.
 * - Supports a horizontal-scroll data model: each block carries its [TimelineBlock.position]
 *   so consumers can layout items without re-indexing.
 * - No UI framework specifics — this is a data-only projection.
 */
class EventTimelineRenderer {

    /**
     * Renders [events] into an immutable, position-indexed list of [TimelineBlock]s.
     * The input list is consumed in order; the output preserves that order.
     */
    fun render(events: List<Event>): List<TimelineBlock> =
        events.mapIndexed { index, event ->
            TimelineBlock(
                type = event.type,
                status = deriveStatus(event),
                position = index
            )
        }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun deriveStatus(event: Event): String {
        val override = event.payload["status"] as? String
        if (override != null) return override
        return labelFor(event.type)
    }

    private fun labelFor(type: String): String = when (type) {
        "intent_submitted"    -> "submitted"
        "contracts_generated" -> "generated"
        "contracts_ready"     -> "ready"
        "contracts_approved"  -> "approved"
        "execution_started"   -> "started"
        "contract_started"    -> "in_progress"
        "contract_completed"  -> "completed"
        "contract_failed"     -> "failed"
        "execution_completed" -> "completed"
        "assembly_started"    -> "assembling"
        "assembly_validated"  -> "validated"
        "assembly_completed"  -> "done"
        "task_assigned"       -> "assigned"
        "task_started"        -> "running"
        "task_completed"      -> "completed"
        "task_validated"      -> "validated"
        "task_failed"         -> "failed"
        "contractor_reassigned" -> "reassigned"
        else                  -> "unknown"
    }
}
