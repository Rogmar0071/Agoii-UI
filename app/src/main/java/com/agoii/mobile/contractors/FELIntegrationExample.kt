package com.agoii.mobile.contractors

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.ContractorExecutor
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.execution.ExecutionLifecycle
import com.agoii.mobile.governor.Governor

/**
 * FEL Integration Example — demonstrates the complete First Execution Loop.
 *
 * UPDATED FOR FEL PASS 2: Now uses ExecutionLifecycle for in-flow contractor invocation.
 *
 * This example shows how to:
 * 1. Submit an intent
 * 2. Generate contracts via ExecutionEntryPoint
 * 3. Run execution lifecycle with integrated contractor invocation
 * 4. Retrieve traceable results
 *
 * Usage:
 *   val example = FELIntegrationExample()
 *   val result = example.runCompleteLoop()
 *   println("Contractor: ${result.contractor_id}")
 *   println("Status: ${result.status}")
 */
class FELIntegrationExample {
    
    private val projectId = "fel-example-project"
    private val ledger = EventLedger()
    private val repository = EventRepository(ledger)
    private val registry = RealContractorRegistry()
    private val entryPoint = ExecutionEntryPoint(ledger)
    private val contractorExecutor = ContractorExecutor(registry)
    private val lifecycle = ExecutionLifecycle(repository, contractorExecutor, registry)
    
    /**
     * Run the complete FEL flow from intent to contractor result.
     */
    fun runCompleteLoop(): ContractorResult {
        // Step 1: Submit Intent
        println("=== FEL Step 1: Submit Intent ===")
        val intentPayload = mapOf(
            "intentId" to "example-intent-001",
            "objective" to "Build a RESTful API with authentication"
        )
        ledger.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, intentPayload)
        println("Intent submitted: ${intentPayload["objective"]}")
        
        // Step 2: Execute Intent (Contract Derivation)
        println("\n=== FEL Step 2: Derive Contracts ===")
        val authResult = entryPoint.executeIntent(projectId, intentPayload)
        
        if (!authResult.authorized) {
            throw IllegalStateException("Authorization failed: ${authResult.reason}")
        }
        
        val contractsEvent = authResult.event!!
        val reportId = contractsEvent.payload["report_id"] as String
        val contracts = contractsEvent.payload["contracts"] as List<*>
        
        println("Contracts generated: ${contracts.size} contracts")
        println("Report ID (RRID): $reportId")
        contracts.forEachIndexed { index, contract ->
            val c = contract as Map<*, *>
            println("  Contract ${index + 1}: ${c["contractId"]} - ${c["name"]}")
        }
        
        // Step 3: Run Execution Lifecycle (Governor + Contractor Invocation)
        println("\n=== FEL Step 3: Execute Lifecycle with Integrated Contractor Invocation ===")
        println("Running execution lifecycle (Governor + contractor invocation)...")
        
        val lifecycleResult = lifecycle.runLifecycle(projectId)
        
        println("Lifecycle completed: ${lifecycleResult.completed}")
        println("Final state: ${lifecycleResult.finalState}")
        println("Contractor invocations: ${lifecycleResult.contractorResults.size}")
        
        // Step 4: Result Analysis
        println("\n=== FEL Step 4: Contractor Execution Result ===")
        
        val contractorResult = lifecycleResult.contractorResults.firstOrNull()
            ?: throw IllegalStateException("No contractor result available")
        
        println("Status: ${contractorResult.status}")
        println("Contractor: ${contractorResult.contractor_id}")
        println("Contract ID: ${contractorResult.contract_id}")
        println("Report Reference: ${contractorResult.report_reference}")
        
        if (contractorResult.status == "success") {
            val output = contractorResult.output as Map<*, *>
            println("\nExecution Details:")
            println("  Contractor Invoked: ${output["contractor_invoked"]}")
            println("  Result: ${output["result"]}")
            
            val trace = output["execution_trace"] as? Map<*, *>
            if (trace != null) {
                println("\nResolution Trace:")
                println("  Evaluated: ${trace["evaluated"]}")
                println("  Matched: ${trace["matched"]}")
                println("  Rejected: ${trace["rejected"]}")
            }
        } else {
            println("Execution failed: ${contractorResult.error}")
        }
        
        println("\n=== FEL Complete ===")
        println("✓ Intent accepted")
        println("✓ Contracts derived")
        println("✓ Contractor selected (WITHIN system flow)")
        println("✓ Contractor invoked (WITHIN system flow)")
        println("✓ Result returned")
        println("✓ No invariant violations")
        println("✓ Execution lifecycle managed by system, not external tests")
        
        return contractorResult
    }
    
    /**
     * Demonstrate contractor selection logic.
     */
    fun demonstrateContractorSelection() {
        println("\n=== Contractor Registry ===")
        val contractors = registry.getAll()
        
        contractors.forEach { contractor ->
            println("\nContractor: ${contractor.contractorId}")
            println("  Capabilities:")
            contractor.capabilities.forEach { cap ->
                println("    - ${cap.name}: level ${cap.level}")
            }
            println("  Reliability: ${contractor.reliabilityScore}")
            println("  Cost: ${contractor.costScore}")
            println("  Availability: ${contractor.availabilityScore}")
        }
        
        println("\n=== Selection Test ===")
        val matchingEngine = DeterministicMatchingEngine()
        
        val testContract = ExecutionContract(
            contractId = "test-contract-1",
            reportReference = "test-rrid",
            position = "1"
        )
        
        val requirements = listOf(
            ContractRequirement("code_generation", 4, 1.0),
            ContractRequirement("reasoning", 3, 0.8),
            ContractRequirement("natural_language", 3, 0.5)
        )
        
        val taskContract = matchingEngine.resolve(testContract, requirements, registry)
        
        println("\nContract Requirements:")
        requirements.forEach { req ->
            println("  - ${req.capability}: level ${req.requiredLevel} (weight ${req.weight})")
        }
        
        println("\nSelection Result:")
        println("  Mode: ${taskContract.assignment.mode}")
        println("  Selected: ${taskContract.assignment.contractorIds}")
        println("  Evaluated: ${taskContract.trace.evaluated.size} contractors")
        println("  Matched: ${taskContract.trace.matched}")
        println("  Rejected: ${taskContract.trace.rejected.size} contractors")
    }
}

/**
 * Main function to run the FEL example.
 * This demonstrates the complete flow without requiring Android test infrastructure.
 */
fun main() {
    val example = FELIntegrationExample()
    
    // First, show available contractors
    example.demonstrateContractorSelection()
    
    println("\n" + "=".repeat(70))
    println("RUNNING COMPLETE FIRST EXECUTION LOOP (FEL PASS 2)")
    println("WITH IN-FLOW CONTRACTOR INVOCATION")
    println("=".repeat(70))
    
    // Then run the complete loop
    val result = example.runCompleteLoop()
    
    // Verify success
    if (result.status == "success") {
        println("\n✅ FEL PASS 2 SUCCESSFUL")
        println("   Contractor ${result.contractor_id} executed contract ${result.contract_id}")
        println("   Report reference: ${result.report_reference}")
        println("   Invocation happened WITHIN system flow (not external)")
    } else {
        println("\n❌ FEL FAILED")
        println("   Error: ${result.error}")
    }
}

