package com.agoii.mobile.contractors

/**
 * RealContractorRegistry — implementation of ContractorRegistry interface
 * with real contractors (OpenAI, Gemini, Copilot).
 *
 * This registry provides the three contractors required for the First Execution Loop (FEL).
 * Each contractor is defined with capabilities, reliability, cost, and availability scores.
 */
class RealContractorRegistry : ContractorRegistry {
    
    private val contractors: List<ContractorProfile> = listOf(
        // OpenAI Contractor
        ContractorProfile(
            contractorId = "openai-gpt4",
            capabilities = listOf(
                Capability("code_generation", 5),
                Capability("natural_language", 5),
                Capability("reasoning", 4),
                Capability("constraint_obedience", 4)
            ),
            reliabilityScore = 0.92,
            costScore = 0.7,
            availabilityScore = 0.95
        ),
        
        // Gemini Contractor
        ContractorProfile(
            contractorId = "gemini-pro",
            capabilities = listOf(
                Capability("code_generation", 4),
                Capability("natural_language", 5),
                Capability("reasoning", 5),
                Capability("constraint_obedience", 4)
            ),
            reliabilityScore = 0.90,
            costScore = 0.6,
            availabilityScore = 0.93
        ),
        
        // Copilot Contractor
        ContractorProfile(
            contractorId = "github-copilot",
            capabilities = listOf(
                Capability("code_generation", 5),
                Capability("natural_language", 4),
                Capability("reasoning", 3),
                Capability("constraint_obedience", 3)
            ),
            reliabilityScore = 0.88,
            costScore = 0.5,
            availabilityScore = 0.97
        )
    )
    
    override fun getAll(): List<ContractorProfile> = contractors
}
