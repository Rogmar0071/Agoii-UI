package com.agoii.mobile.contractor.registry

import com.agoii.mobile.contractor.ContractorCapabilityVector
import com.agoii.mobile.contractor.ContractorProfile
import com.agoii.mobile.contractor.VerificationStatus

object HumanCommunicationContractor {

    val PROFILE = ContractorProfile(
        id = "internal.human.communication",
        source = "human",
        status = VerificationStatus.VERIFIED,

        capabilities = ContractorCapabilityVector(
            constraintObedience = 3,
            structuralAccuracy  = 2,
            driftScore          = 2,
            complexityCapacity  = 2,
            reliability         = 3
        ),

        verificationCount = 1,
        successCount = 0,
        failureCount = 0,
        notes = listOf("Human interaction contractor")
    )
}
