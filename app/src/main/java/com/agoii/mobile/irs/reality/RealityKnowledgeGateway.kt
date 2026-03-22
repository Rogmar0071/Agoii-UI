package com.agoii.mobile.irs.reality

import com.agoii.mobile.irs.IntentData
import com.agoii.mobile.irs.KnowledgeFact

/**
 * RealityKnowledgeGateway — controlled, deterministic knowledge retrieval interface.
 *
 * IRS-05 Contract Rules:
 *  - ALL retrieved facts must be traceable (source is always set).
 *  - ALL lookups are deterministic — no randomness, no live network calls.
 *  - When a domain is unknown the gateway returns an empty list (NO guessing).
 *  - External callers may inject a custom knowledge store via the [knowledgeStore]
 *    constructor parameter to support controlled test doubles.
 *
 * Knowledge domains:
 *  - "environment" — runtime platforms, cloud providers, execution models.
 *  - "dependency"  — build tools, libraries, external services.
 *  - "constraint"  — execution constraints, compatibility and latency rules.
 *  - "objective"   — goal reachability and scope-related facts.
 *  - "resources"   — team, budget, and tooling availability facts.
 *
 * @property knowledgeStore Injected knowledge base; defaults to [defaultKnowledgeStore].
 */
class RealityKnowledgeGateway(
    private val knowledgeStore: Map<String, List<KnowledgeFact>> = defaultKnowledgeStore
) {

    companion object {
        /** Authoritative curated knowledge base (deterministic, always reproducible). */
        val defaultKnowledgeStore: Map<String, List<KnowledgeFact>> = mapOf(

            "environment" to listOf(
                KnowledgeFact("environment", "cloud environments require internet connectivity", 0.95, "reality-kb-env"),
                KnowledgeFact("environment", "AWS, GCP, and Azure are production-grade cloud platforms", 0.95, "reality-kb-env"),
                KnowledgeFact("environment", "serverless environments have cold-start latency constraints", 0.90, "reality-kb-env"),
                KnowledgeFact("environment", "Kubernetes requires container orchestration infrastructure", 0.90, "reality-kb-env"),
                KnowledgeFact("environment", "on-premise environments have limited elastic scaling", 0.85, "reality-kb-env"),
                KnowledgeFact("environment", "edge environments have restricted compute and storage", 0.80, "reality-kb-env"),
                KnowledgeFact("environment", "hybrid environments combine cloud and on-premise components", 0.85, "reality-kb-env")
            ),

            "dependency" to listOf(
                KnowledgeFact("dependency", "Gradle is the standard Android build tool", 0.95, "reality-kb-dep"),
                KnowledgeFact("dependency", "Docker requires container runtime support on the host", 0.90, "reality-kb-dep"),
                KnowledgeFact("dependency", "npm is the standard Node.js package manager", 0.95, "reality-kb-dep"),
                KnowledgeFact("dependency", "Kotlin is a JVM-based language requiring JDK", 0.95, "reality-kb-dep"),
                KnowledgeFact("dependency", "PostgreSQL is a relational database requiring persistent storage", 0.90, "reality-kb-dep"),
                KnowledgeFact("dependency", "Kafka requires Zookeeper or KRaft for cluster coordination", 0.85, "reality-kb-dep"),
                KnowledgeFact("dependency", "Redis is an in-memory cache with optional persistence", 0.90, "reality-kb-dep")
            ),

            "constraint" to listOf(
                KnowledgeFact("constraint", "real-time processing requires sub-100ms end-to-end latency", 0.90, "reality-kb-con"),
                KnowledgeFact("constraint", "offline-capable systems require local data persistence", 0.90, "reality-kb-con"),
                KnowledgeFact("constraint", "serverless functions cannot maintain persistent connections", 0.88, "reality-kb-con"),
                KnowledgeFact("constraint", "GDPR compliance requires data residency controls", 0.92, "reality-kb-con"),
                KnowledgeFact("constraint", "zero-downtime deployments require redundant infrastructure", 0.85, "reality-kb-con"),
                KnowledgeFact("constraint", "horizontal scaling requires stateless application design", 0.87, "reality-kb-con")
            ),

            "objective" to listOf(
                KnowledgeFact("objective", "build objectives require a defined delivery scope", 0.88, "reality-kb-obj"),
                KnowledgeFact("objective", "system objectives must be measurable to be certifiable", 0.90, "reality-kb-obj"),
                KnowledgeFact("objective", "migration objectives require source system access", 0.85, "reality-kb-obj"),
                KnowledgeFact("objective", "integration objectives require compatible API contracts", 0.87, "reality-kb-obj")
            ),

            "resources" to listOf(
                KnowledgeFact("resources", "team availability directly constrains delivery timeline", 0.90, "reality-kb-res"),
                KnowledgeFact("resources", "budget constraints limit infrastructure choices", 0.88, "reality-kb-res"),
                KnowledgeFact("resources", "unavailable resources block dependent constraints", 0.92, "reality-kb-res"),
                KnowledgeFact("resources", "external services require contractual availability guarantees", 0.85, "reality-kb-res")
            )
        )
    }

    /**
     * Query the knowledge store for facts relevant to [domain] and [intent].
     *
     * Retrieval rules:
     *  - Returns all facts whose [KnowledgeFact.domain] matches [domain] exactly.
     *  - Additionally includes facts that contain keywords from the relevant intent field.
     *  - Returns an empty list when [domain] is not in the knowledge store.
     *  - NEVER invents facts; only returns stored entries.
     *
     * @param domain The knowledge domain to query (e.g. "environment", "dependency").
     * @param intent The current intent (used for keyword-based relevance filtering).
     * @return Ordered list of relevant [KnowledgeFact]s; empty when domain is unknown.
     */
    fun query(domain: String, intent: IntentData): List<KnowledgeFact> {
        val domainFacts = knowledgeStore[domain] ?: return emptyList()

        // Extract the field value relevant to the domain for keyword matching.
        val fieldValue = when (domain) {
            "environment" -> intent.environment.value
            "dependency"  -> "${intent.resources.value} ${intent.constraints.value}"
            "constraint"  -> intent.constraints.value
            "objective"   -> intent.objective.value
            "resources"   -> intent.resources.value
            else          -> ""
        }.lowercase()

        // Return all domain facts that are generally applicable (high credibility) or
        // specifically relevant to the field value keywords.
        return domainFacts.filter { fact ->
            fact.credibilityScore >= 0.85 ||
            fact.claim.lowercase().split(" ").any { word ->
                word.length > 3 && fieldValue.contains(word)
            }
        }
    }

    /**
     * Query all configured domains for facts relevant to [intent].
     *
     * @return Map of domain → retrieved facts; only domains with ≥ 1 matching fact are included.
     */
    fun queryAll(intent: IntentData): Map<String, List<KnowledgeFact>> =
        knowledgeStore.keys
            .associateWith { domain -> query(domain, intent) }
            .filter { (_, facts) -> facts.isNotEmpty() }
}
