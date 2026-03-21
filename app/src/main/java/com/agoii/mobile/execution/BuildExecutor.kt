package com.agoii.mobile.execution

import java.io.File

/**
 * BuildExecutor — performs the real action for each contract step.
 *
 * Contract name → action mapping (exact):
 *
 *   "Core Setup"   → Validate build environment (gradlew exists and is executable).
 *   "Integration"  → Execute Gradle build (./gradlew assembleDebug).
 *   "Validation"   → Validate APK artifact via BuildValidator.
 *
 * Rules:
 *  - Called from the bridge layer ONLY when event.type == contract_started.
 *  - Returns true  → execution passed; allow Governor to emit contract_completed.
 *  - Returns false → execution failed; bridge must block contract_completed.
 *  - MUST NOT append events.
 *  - MUST NOT trigger the next step.
 *  - MUST NOT loop contracts.
 */
class BuildExecutor {

    private val validator = BuildValidator()

    /**
     * Execute the action for the given contract name.
     * Returns true on success, false on failure.
     */
    fun execute(contractName: String): Boolean = when (contractName) {
        "Core Setup"  -> checkBuildEnvironment()
        "Integration" -> executeGradleBuild()
        "Validation"  -> validator.validate()
        else          -> true
    }

    /**
     * CASE: "Core Setup"
     * Checks that the Gradle wrapper script exists and is executable.
     * If either condition is missing → return false (do not complete contract).
     */
    private fun checkBuildEnvironment(): Boolean {
        val gradlew = File("gradlew")
        return gradlew.exists() && gradlew.canExecute()
    }

    /**
     * CASE: "Integration"
     * Executes ./gradlew assembleDebug.
     * Captures exit code and stdout/stderr (consumed to prevent process blocking;
     * not stored per spec — DO NOT append events here).
     * Returns true only if exit code == 0.
     */
    private fun executeGradleBuild(): Boolean {
        return try {
            val process = ProcessBuilder("./gradlew", "assembleDebug")
                .redirectErrorStream(true)
                .start()
            // Consume stdout/stderr to prevent the process from blocking on a full pipe buffer.
            process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}
