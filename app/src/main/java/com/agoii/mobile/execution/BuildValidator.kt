package com.agoii.mobile.execution

import java.io.File

/**
 * BuildValidator — confirms the APK artifact produced by the Integration contract is valid.
 *
 * Rules:
 *  - Returns true  → allow contract_completed to be emitted by the Governor.
 *  - Returns false → block completion; do not emit contract_completed.
 *  - No side effects; pure validation only.
 */
class BuildValidator {

    /**
     * Validates the debug APK produced by the Gradle build.
     *
     * Criteria (both must pass):
     *  1. File exists at the expected output path.
     *  2. File size > 0.
     */
    fun validate(): Boolean {
        val apk = File("app/build/outputs/apk/debug/app-debug.apk")
        return apk.exists() && apk.length() > 0
    }
}
