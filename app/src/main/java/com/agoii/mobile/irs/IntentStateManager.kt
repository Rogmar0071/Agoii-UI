package com.agoii.mobile.irs

/**
 * IntentStateManager — append-only session state for IRS-02.
 *
 * Rules (enforced by this class):
 *  - State is append-only: no field is ever overwritten in-place.
 *  - Each [appendState] call records a new snapshot of the session.
 *  - [getLatestState] always returns the most recently appended snapshot.
 *  - [getHistory] provides the full ordered audit trail.
 *  - Sessions are identified by [sessionId] (caller-generated UUID).
 *  - The Evidence Store is session-bound and ephemeral — no cross-session sharing.
 *
 * RULE 2 (IRS-02): No hidden state — all state lives in [IntentState].
 */
class IntentStateManager {

    /** Maps sessionId → ordered list of state snapshots (oldest first). */
    private val sessions: MutableMap<String, MutableList<IntentState>> = mutableMapOf()

    /**
     * Initialise a new session with the provided raw input.
     * The session is created with status [SessionStatus.INITIATED].
     * If a session with [sessionId] already exists, this is a no-op and
     * the existing initial state is returned.
     */
    fun initSession(
        sessionId: String,
        rawInput: String,
        optionalContext: Map<String, Any> = emptyMap()
    ): IntentState {
        if (sessions.containsKey(sessionId)) return getLatestState(sessionId)!!
        val initial = IntentState(
            sessionId       = sessionId,
            rawInput        = rawInput,
            optionalContext = optionalContext,
            status          = SessionStatus.INITIATED,
            stepLog         = listOf("Session $sessionId initiated")
        )
        sessions[sessionId] = mutableListOf(initial)
        return initial
    }

    /**
     * Return the most recently appended [IntentState] for [sessionId],
     * or null if the session does not exist.
     */
    fun getLatestState(sessionId: String): IntentState? =
        sessions[sessionId]?.lastOrNull()

    /**
     * Append a new [IntentState] snapshot to the session.
     * The snapshot is added to the end of the session's history list.
     *
     * RULE: This is the ONLY way to mutate session state.
     * Components MUST NOT store or modify state outside this method.
     */
    fun appendState(sessionId: String, state: IntentState) {
        sessions.getOrPut(sessionId) { mutableListOf() }.add(state)
    }

    /**
     * Return the full ordered history of state snapshots for [sessionId]
     * (oldest first), or an empty list if the session does not exist.
     * This is the traceability audit trail for the session.
     */
    fun getHistory(sessionId: String): List<IntentState> =
        sessions[sessionId]?.toList() ?: emptyList()

    /** Return true if a session with [sessionId] exists. */
    fun hasSession(sessionId: String): Boolean = sessions.containsKey(sessionId)

    /**
     * Remove a terminated session from memory.
     * Called after a session reaches a terminal state to enforce the
     * "no persistent ownership of user data" principle.
     */
    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
    }
}
