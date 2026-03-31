package com.agoii.mobile.contractor.registry

import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.VerificationStatus

object NemoclawContractor {

    val PROFILE = ContractorProfile(
        id = "external.nemoclaw.agent",
        source = "nemoclaw",
        status = VerificationStatus.VERIFIED,

        capabilities = ContractorCapabilityVector(
            constraintObedience = 3,
            structuralAccuracy  = 3,
            driftScore          = 1,
            complexityCapacity  = 3,
            reliability         = 3
        ),

        verificationCount = 1,
        successCount = 0,
        failureCount = 0,
        notes = listOf("Sandboxed external execution contractor")
    )
}
