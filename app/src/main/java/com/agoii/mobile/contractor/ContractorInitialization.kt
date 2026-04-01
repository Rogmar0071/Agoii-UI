package com.agoii.mobile.contractor

import com.agoii.mobile.contractor.registry.HumanCommunicationContractor
import com.agoii.mobile.contractor.registry.NemoclawContractor
import com.agoii.mobile.execution.DriverRegistry
import com.agoii.mobile.execution.ExecutionDriver

// ─────────────────────────────────────────────────────────────
// ContractorInitialization
// ─────────────────────────────────────────────────────────────

/**
 * Deterministic initialization layer for contractor system.
 *
 * RESPONSIBILITIES:
 *  - Populate ContractorRegistry with REQUIRED contractors
 *  - Validate DriverRegistry contains required drivers
 *  - BLOCK execution early if invariants are not satisfied
 *
 * NO SIDE EFFECTS beyond explicit registration.
 * NO FALLBACKS.
 *
 * CONTRACT: INITIALIZATION_LAYER_V1
 */
object ContractorInitialization {

    // ─────────────────────────────────────────────────────────
    // REQUIRED CONTRACTORS
    // ─────────────────────────────────────────────────────────

    private val REQUIRED_CONTRACTORS = listOf(
        NemoclawContractor.PROFILE,
        HumanCommunicationContractor.PROFILE
    )

    private val REQUIRED_DRIVER_SOURCES = listOf(
        "nemoclaw"
    )

    // ─────────────────────────────────────────────────────────
    // INITIALIZE REGISTRY
    // ─────────────────────────────────────────────────────────

    fun initializeRegistry(registry: ContractorRegistry) {
        if (registry.getAll().isNotEmpty()) return

        REQUIRED_CONTRACTORS.forEach { contractor ->
            registry.register(contractor)
        }
    }

    // ─────────────────────────────────────────────────────────
    // VALIDATE REGISTRY
    // ─────────────────────────────────────────────────────────

    fun validateRegistry(registry: ContractorRegistry) {
        val registeredIds = registry.getAll().map { it.id }.toSet()

        val missing = REQUIRED_CONTRACTORS
            .map { it.id }
            .filterNot { it in registeredIds }

        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "CONTRACTOR_INITIALIZATION_BLOCKED: Missing contractors: $missing"
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    // VALIDATE DRIVERS
    // ─────────────────────────────────────────────────────────

    fun validateDrivers(driverRegistry: DriverRegistry) {
        val missing = REQUIRED_DRIVER_SOURCES.filter {
            driverRegistry.resolve(it) == null
        }

        if (missing.isNotEmpty()) {
            throw IllegalStateException(
                "CONTRACTOR_INITIALIZATION_BLOCKED: Missing drivers for sources: $missing"
            )
        }
    }

    // ─────────────────────────────────────────────────────────
    // FULL INITIALIZATION CHECK
    // ─────────────────────────────────────────────────────────

    fun enforce(
        registry: ContractorRegistry,
        driverRegistry: DriverRegistry
    ) {
        initializeRegistry(registry)
        validateRegistry(registry)
        validateDrivers(driverRegistry)
    }
}
