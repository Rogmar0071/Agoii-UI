package com.agoii.mobile.execution

import com.agoii.mobile.infrastructure.OpenAIClient

// ─── DriverRegistry ───────────────────────────────────────────────────────────

/**
 * DriverRegistry — maps contractor source identifiers to [ExecutionDriver] instances.
 *
 * RULES:
 *  - NO default driver. If a source has no registered driver, [resolve] returns null.
 *  - Callers MUST treat null from [resolve] as a hard block (no fallback allowed).
 *  - Drivers are registered explicitly via [register]; none are wired automatically.
 *  - Stateful: each registry instance maintains its own in-memory store.
 *
 * CONTRACT: AGOII-RCF-EXECUTION-INFRASTRUCTURE-01
 */
class DriverRegistry {

    private val store: MutableMap<String, ExecutionDriver> = mutableMapOf()

    /**
     * Register [driver] for the given [source] identifier.
     *
     * Overwrites any previously registered driver for [source].
     *
     * @param source  Contractor source string (e.g. "llm", "api", "tool").
     * @param driver  The [ExecutionDriver] to associate with [source].
     */
    fun register(source: String, driver: ExecutionDriver) {
        store[source] = driver
    }

    /**
     * Resolve the [ExecutionDriver] for [source].
     *
     * @param source  Contractor source string to look up.
     * @return The registered [ExecutionDriver], or null when none is registered.
     */
    fun resolve(source: String): ExecutionDriver? = store[source]

    /**
     * Create an [LLMContractor] backed by a new [OpenAIClient] and register it
     * under the `"llm"` source key.
     *
     * RULES:
     *  - No automatic registration occurs; this must be called explicitly.
     *  - Config resolution is deferred to [LLMContractor.execute]; registering here
     *    does NOT guarantee execution will succeed — missing config will BLOCK.
     *
     * CONTRACT: AGOII-RCF-LLM-DRIVER-IMPLEMENTATION-01
     */
    fun registerLLMContractor() {
        register("llm", LLMContractor(OpenAIClient()))
    }

    /**
     * Register a NemoClaw [ExecutionDriver] under the `"nemoclaw"` source key.
     *
     * RULES:
     *  - No automatic registration occurs; this must be called explicitly.
     *  - If no driver is registered for "nemoclaw", resolution is BLOCKED.
     *
     * CONTRACT: REGISTER_CONTRACTORS_V1
     */
    fun registerNemoclawDriver(driver: ExecutionDriver) {
        register("nemoclaw", driver)
    }
}
