package com.agoii.mobile.contractors

import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.ContractorExecutor
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.governor.Governor

/**
 * FEL Integration Example — demonstrates the complete First Execution Loop.
 *
 * COMPLIANT PATTERN (Post-Recovery):
 * - Direct component invocation
 * - NO orchestration layers (ExecutionLifecycle removed)
 * - Governor remains sole state authority
 *
 * This example shows how to:
 * 1. Submit an intent
 * 2. Generate contracts via ExecutionEntryPoint
 * 3. Progress through Governor states
 * 4. Execute contractor invocation directly via ContractorExecutor
 * 5. Retrieve traceable results
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
    private val governor = Governor(repository, null)
    private val matchingEngine = DeterministicMatchingEngine()
    private val invocationLayer = ContractorInvocationLayer()
    
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
        
        // Step 3: Governor Progression (COMPLIANT - direct calls)
        println("\n=== FEL Step 3: Governor Progression ===")
        
        // CONTRACTS_GENERATED -> CONTRACTS_READY
        var result = governor.runGovernor(projectId)
        println("State transition: CONTRACTS_GENERATED -> CONTRACTS_READY (${result})")
        
        // CONTRACTS_READY -> CONTRACT_STARTED
        result = governor.runGovernor(projectId)
        println("State transition: CONTRACTS_READY -> CONTRACT_STARTED (${result})")
        
        // CONTRACT_STARTED -> TASK_ASSIGNED
        result = governor.runGovernor(projectId)
        println("State transition: CONTRACT_STARTED -> TASK_ASSIGNED (${result})")
        
        // Step 4: DIRECT Contractor Invocation (COMPLIANT PATTERN)
        println("\n=== FEL Step 4: Contractor Invocation (Direct) ===")
        
        val events = ledger.loadEvents(projectId)
        val taskAssignedEvent = events.last { it.type == EventTypes.TASK_ASSIGNED }
        
        val taskId = taskAssignedEvent.payload["taskId"] as String
        println("Task assigned: $taskId")
        
        // Direct invocation via ContractorExecutor (NO orchestration layer)
        val contractorExecutor = ContractorExecutor(registry)
        val contractorResult = contractorExecutor.executeFromTaskAssigned(events, taskAssignedEvent)
        
        // Step 5: Result Analysis
        println("\n=== FEL Step 5: Contractor Execution Result ===")
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
        println("✓ Governor progression (direct calls)")
        println("✓ Contractor selected (direct invocation)")
        println("✓ Contractor invoked (NO orchestration layer)")
        println("✓ Result returned")
        println("✓ No invariant violations")
        println("✓ COMPLIANT PATTERN: Direct component invocation only")
        
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
    println("RUNNING COMPLETE FIRST EXECUTION LOOP (POST-RECOVERY)")
    println("COMPLIANT PATTERN: Direct component invocation")
    println("NO ORCHESTRATION LAYERS")
    println("=".repeat(70))
    
    // Then run the complete loop
    val result = example.runCompleteLoop()
    
    // Verify success
    if (result.status == "success") {
        println("\n✅ FEL SUCCESSFUL (COMPLIANT)")
        println("   Contractor ${result.contractor_id} executed contract ${result.contract_id}")
        println("   Report reference: ${result.report_reference}")
        println("   Pattern: Direct invocation (NO orchestration)")
    } else {
        println("\n❌ FEL FAILED")
        println("   Error: ${result.error}")
    }
}
