package com.agoii.mobile.irs

/**
 * IntentStateManager — append-only session registry for the IRS.
 *
 * Rules:
 *  - Sessions are NEVER deleted or modified; only new snapshots are appended.
 *  - [replayHistory] always returns the full, ordered snapshot list.
 *  - No destructive operations are exposed.
 *
 * This class is used exclusively by [IrsOrchestrator]; no other module may
 * instantiate or call it directly.
 */
class IntentStateManager {

    /**
     * Internal mutable record. The list of snapshots grows monotonically.
     * The [currentIntent] tracks the latest (possibly scouting-enriched) intent.
     */
    private data class SessionRecord(
        val meta:                    IrsSession,
        val snapshots:               MutableList<IrsSnapshot>,
        val currentIntent:           IntentData,
        val swarmResult:             SwarmResult?,
        val simResult:               SimulationResult?,
        val availableEvidence:       Map<String, List<EvidenceRef>>,
        val scoutReport:             KnowledgeScoutReport?,
        val evidenceValidationResult: EvidenceValidationResult?
    )

    private val records = mutableMapOf<String, SessionRecord>()

    // ─── Session lifecycle ────────────────────────────────────────────────────

    /**
     * Initialise a new session.  Calling this with an existing [sessionId]
     * is a programming error and will throw [IllegalArgumentException].
     */
    fun init(
        sessionId:         String,
        intentData:        IntentData,
        swarmConfig:       SwarmConfig,
        availableEvidence: Map<String, List<EvidenceRef>> = emptyMap()
    ): IrsSession {
        require(!records.containsKey(sessionId)) {
            "Session '$sessionId' already exists — use step() to advance it"
        }
        val session = IrsSession(
            sessionId   = sessionId,
            intentData  = intentData,
            swarmConfig = swarmConfig,
            history     = emptyList()
        )
        records[sessionId] = SessionRecord(
            meta                    = session,
            snapshots               = mutableListOf(),
            currentIntent           = intentData,
            swarmResult             = null,
            simResult               = null,
            availableEvidence       = availableEvidence,
            scoutReport             = null,
            evidenceValidationResult = null
        )
        return session
    }

    /**
     * Append a snapshot after a stage completes.
     *
     * @param updatedIntent           Supply when scouting has enriched the intent.
     * @param swarmResult             Supply when swarm validation has produced a result.
     * @param simResult               Supply when simulation has produced a result.
     * @param scoutReport             Supply when knowledge scouting has produced a report.
     * @param evidenceValidationResult Supply when evidence validation has run.
     * @return The updated immutable [IrsSession] view.
     */
    fun append(
        sessionId:                String,
        snapshot:                 IrsSnapshot,
        updatedIntent:            IntentData?               = null,
        swarmResult:              SwarmResult?              = null,
        simResult:                SimulationResult?         = null,
        scoutReport:              KnowledgeScoutReport?     = null,
        evidenceValidationResult: EvidenceValidationResult? = null
    ): IrsSession {
        val record = records[sessionId]
            ?: error("Session '$sessionId' does not exist")
        record.snapshots.add(snapshot)
        val newRecord = record.copy(
            currentIntent            = updatedIntent            ?: record.currentIntent,
            swarmResult              = swarmResult              ?: record.swarmResult,
            simResult                = simResult                ?: record.simResult,
            scoutReport              = scoutReport              ?: record.scoutReport,
            evidenceValidationResult = evidenceValidationResult ?: record.evidenceValidationResult
        )
        records[sessionId] = newRecord
        return buildSession(newRecord)
    }

    // ─── Read accessors ───────────────────────────────────────────────────────

    /** Return the current immutable session view, or null if not found. */
    fun getSession(sessionId: String): IrsSession? =
        records[sessionId]?.let(::buildSession)

    /** Return the latest working intent (possibly enriched by scouting). */
    fun currentIntent(sessionId: String): IntentData? =
        records[sessionId]?.currentIntent

    /** Return the swarm result stored during step 4, or null. */
    fun swarmResult(sessionId: String): SwarmResult? =
        records[sessionId]?.swarmResult

    /** Return the simulation result stored during step 5, or null. */
    fun simResult(sessionId: String): SimulationResult? =
        records[sessionId]?.simResult

    /** Return the supplementary evidence pool for scouting, or empty map. */
    fun availableEvidence(sessionId: String): Map<String, List<EvidenceRef>> =
        records[sessionId]?.availableEvidence ?: emptyMap()

    /** Return the knowledge scout report stored during scouting, or null. */
    fun scoutReport(sessionId: String): KnowledgeScoutReport? =
        records[sessionId]?.scoutReport

    /** Return the evidence validation result stored during evidence validation, or null. */
    fun evidenceValidationResult(sessionId: String): EvidenceValidationResult? =
        records[sessionId]?.evidenceValidationResult

    /**
     * Replay the full snapshot history for [sessionId].
     * Returns the complete ordered list; never null, never truncated.
     */
    fun replayHistory(sessionId: String): List<IrsSnapshot> =
        records[sessionId]?.snapshots?.toList() ?: emptyList()

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun buildSession(record: SessionRecord): IrsSession =
        record.meta.copy(
            intentData = record.currentIntent,
            history    = record.snapshots.toList()
        )
}
