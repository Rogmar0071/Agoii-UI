package com.agoii.mobile.execution

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
}
