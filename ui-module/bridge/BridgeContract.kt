package agoii.ui.bridge

/**
 * Defines the contract that [CoreBridge] must satisfy.
 *
 * Every method is a pure query or a controlled command — no internal state
 * leaks across the boundary. The UI module programs against this interface;
 * the concrete [CoreBridge] implementation is injected at startup.
 *
 * ALLOWED operations:
 *   - Load events (read-only)
 *   - Replay structural state (read-only)
 *   - Process user interaction (write via core authority)
 *
 * FORBIDDEN:
 *   - Direct ledger writes
 *   - Direct execution authority access
 *   - Direct governor access
 */
interface BridgeContract {

    /**
     * Loads all ledger events for the given project.
     * Returns an immutable list of [UIEvent]s in ledger order.
     */
    fun loadEvents(projectId: String): List<UIEvent>

    /**
     * Replays the ledger and returns the authoritative [UIReplayState].
     * The UI module reads ONLY [UIReplayState.auditView].
     */
    fun replayState(projectId: String): UIReplayState

    /**
     * Submits user input through the core execution pipeline.
     * Returns a human-readable response string.
     *
     * @throws IllegalStateException if the core pipeline rejects the input.
     */
    fun processInteraction(projectId: String, input: String): String
}
