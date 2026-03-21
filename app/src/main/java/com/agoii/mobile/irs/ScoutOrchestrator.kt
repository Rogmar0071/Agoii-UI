package com.agoii.mobile.irs

/**
 * ScoutOrchestrator — Knowledge Scout Orchestrator (IRS-02 Component 5).
 *
 * Manages three independent scout agents:
 *  1. environment_lookup  — validates the environment field references a recognisable platform.
 *  2. dependency_check    — validates the resources field lists concrete dependencies.
 *  3. feasibility_scan    — cross-validates objective coherence with environment and resources.
 *
 * Agent constraints (enforced here):
 *  - Each agent returns [ScoutEvidence] with confidence ∈ [0.0, 1.0] and a reasoning trace.
 *  - Agents MUST NOT mutate session state.
 *  - Agents MUST NOT bypass the orchestrator.
 *  - Output is deterministic for the same input (no randomness, no external I/O).
 *
 * Trigger conditions (per IRS-02 spec):
 *  - Unresolved fields in intent draft.
 *  - Environment uncertainty.
 *  - Feasibility unknown.
 *
 * Since scouts run after gap detection (which guarantees all fields are present),
 * each scout validates the QUALITY of the provided values, not their presence.
 */
object ScoutOrchestrator {

    // ── Known platform keywords for environment_lookup ───────────────────────

    private val KNOWN_PLATFORMS = setOf(
        "android", "ios", "web", "linux", "windows", "macos", "jvm",
        "kotlin", "java", "python", "node", "react", "flutter", "api",
        "cloud", "aws", "gcp", "azure", "docker", "kubernetes"
    )

    // ── Known dependency patterns for dependency_check ───────────────────────

    private val DEPENDENCY_PATTERNS = setOf(
        "sdk", "lib", "library", "framework", "junit", "gradle", "maven",
        "npm", "pip", "pkg", "package", "module", "dependency", "plugin",
        "tool", "cli", "api", "stdlib", "runtime", "compiler"
    )

    /**
     * Run all three scout agents against [draft] and return their combined evidence set.
     * Each agent is isolated: its output does not affect the inputs of the others.
     *
     * @param draft the current intent draft (all fields are non-null at this point)
     * @param gaps  any residual gap names forwarded for contextual awareness (typically empty)
     */
    fun runScouts(draft: IntentDraft, gaps: List<String> = emptyList()): List<ScoutEvidence> =
        listOfNotNull(
            environmentLookup(draft),
            dependencyCheck(draft),
            feasibilityScan(draft, gaps)
        )

    // ─────────────────────────────────────────────────────────────────────────
    // SCOUT 1 — environment_lookup
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates that the environment field references at least one recognisable
     * platform, runtime, or deployment target.
     *
     * Confidence model:
     *  - 1.0  if ≥2 known platform keywords found
     *  - 0.7  if exactly 1 known platform keyword found
     *  - 0.3  if no known keywords but field is non-trivial
     */
    private fun environmentLookup(draft: IntentDraft): ScoutEvidence {
        val env    = draft.environment ?: ""
        val lower  = env.lowercase()
        val hits   = KNOWN_PLATFORMS.filter { it in lower }
        val (confidence, finding) = when {
            hits.size >= 2 -> 1.0f to "Environment references recognised platforms: ${hits.joinToString(", ")}"
            hits.size == 1 -> 0.7f to "Environment references one recognised platform: ${hits.first()}"
            else           -> 0.3f to "Environment field present but no recognised platform keywords detected"
        }
        return ScoutEvidence(
            field          = "environment",
            source         = "environment_lookup",
            content        = finding,
            confidence     = confidence,
            reasoningTrace = buildString {
                appendLine("Scout: environment_lookup")
                appendLine("Input: \"${env.take(80)}\"")
                appendLine("Known platform scan: checked ${KNOWN_PLATFORMS.size} keywords")
                appendLine("Hits: ${if (hits.isEmpty()) "none" else hits.joinToString(", ")}")
                append("Conclusion: $finding")
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCOUT 2 — dependency_check
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Validates that the resources field lists concrete, specific dependencies
     * rather than vague descriptions.
     *
     * Confidence model:
     *  - 1.0  if ≥2 dependency pattern keywords found
     *  - 0.7  if exactly 1 dependency keyword found
     *  - 0.4  if field contains comma-separated items (suggests a list)
     *  - 0.2  otherwise
     */
    private fun dependencyCheck(draft: IntentDraft): ScoutEvidence {
        val res   = draft.resources ?: ""
        val lower = res.lowercase()
        val hits  = DEPENDENCY_PATTERNS.filter { it in lower }
        val hasList = res.contains(',')
        val (confidence, finding) = when {
            hits.size >= 2 -> 1.0f to "Resources field lists concrete dependencies: ${hits.joinToString(", ")}"
            hits.size == 1 -> 0.7f to "Resources field contains one recognised dependency pattern: ${hits.first()}"
            hasList        -> 0.4f to "Resources field appears to be a comma-separated list (structure recognised)"
            else           -> 0.2f to "Resources field is present but no concrete dependency patterns detected"
        }
        return ScoutEvidence(
            field          = "resources",
            source         = "dependency_check",
            content        = finding,
            confidence     = confidence,
            reasoningTrace = buildString {
                appendLine("Scout: dependency_check")
                appendLine("Input: \"${res.take(80)}\"")
                appendLine("Dependency pattern scan: checked ${DEPENDENCY_PATTERNS.size} patterns")
                appendLine("Hits: ${if (hits.isEmpty()) "none" else hits.joinToString(", ")}")
                appendLine("Comma-separated list: $hasList")
                append("Conclusion: $finding")
            }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SCOUT 3 — feasibility_scan
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Cross-validates the coherence between [IntentDraft.objective],
     * [IntentDraft.environment], and [IntentDraft.resources].
     *
     * Checks:
     *  - Objective, environment, and resources share at least one common content word
     *    (suggesting they describe the same domain).
     *  - No field is a trivial single word.
     *
     * Confidence model:
     *  - 1.0  if ≥2 shared domain words detected across the three fields
     *  - 0.6  if exactly 1 shared domain word
     *  - 0.4  if all three fields are substantive (≥5 chars each) but no overlap
     *  - 0.2  otherwise
     */
    private fun feasibilityScan(draft: IntentDraft, gaps: List<String>): ScoutEvidence {
        val obj  = draft.objective ?: ""
        val env  = draft.environment ?: ""
        val res  = draft.resources ?: ""

        val objWords = contentWords(obj)
        val envWords = contentWords(env)
        val resWords = contentWords(res)
        val shared   = objWords.intersect(envWords + resWords)

        val allSubstantive = listOf(obj, env, res).all { it.length >= 5 }

        val (confidence, finding) = when {
            shared.size >= 2 -> 1.0f to "High domain coherence: ${shared.size} shared keywords across objective, environment, and resources"
            shared.size == 1 -> 0.6f to "Moderate domain coherence: 1 shared keyword (\"${shared.first()}\")"
            allSubstantive   -> 0.4f to "Fields are substantive but share no common domain keywords — verify alignment"
            else             -> 0.2f to "Low feasibility confidence: fields may not describe a coherent intent"
        }

        return ScoutEvidence(
            field          = "objective",
            source         = "feasibility_scan",
            content        = finding,
            confidence     = confidence,
            reasoningTrace = buildString {
                appendLine("Scout: feasibility_scan")
                appendLine("Objective words: ${objWords.take(10)}")
                appendLine("Environment words: ${envWords.take(10)}")
                appendLine("Resources words:   ${resWords.take(10)}")
                appendLine("Shared domain words: ${if (shared.isEmpty()) "none" else shared.take(5).joinToString(", ")}")
                appendLine("All fields substantive: $allSubstantive")
                appendLine("Residual gaps forwarded: ${gaps.ifEmpty { listOf("none") }}")
                append("Conclusion: $finding")
            }
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Extract meaningful content words from a string.
     * Strips punctuation, lowercases, and removes common stop words.
     */
    private fun contentWords(text: String): Set<String> {
        val stopWords = setOf(
            "a", "an", "the", "and", "or", "for", "of", "in", "on", "at",
            "to", "be", "is", "it", "as", "by", "with", "all", "no", "any",
            "must", "should", "will", "can", "may", "use", "used", "that",
            "this", "are", "has", "have", "from", "not", "but", "using"
        )
        return text.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 2 && it !in stopWords }
            .toSet()
    }
}
