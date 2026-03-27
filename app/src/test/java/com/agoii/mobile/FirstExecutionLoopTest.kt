package com.agoii.mobile

import com.agoii.mobile.contractors.*
import com.agoii.mobile.core.Event
import com.agoii.mobile.core.EventLedger
import com.agoii.mobile.core.EventRepository
import com.agoii.mobile.core.EventTypes
import com.agoii.mobile.execution.ExecutionEntryPoint
import com.agoii.mobile.governor.Governor
import org.junit.Test
import org.junit.Assert.*

/**
 * FirstExecutionLoopTest — validates the complete FEL flow.
 *
 * Tests:
 * 1. Intent creation
 * 2. Contract derivation via ExecutionEntryPoint
 * 3. Governor progression to TASK_ASSIGNED
 * 4. Contractor selection via ContractorsModule
 * 5. Real contractor invocation
 * 6. Result traceability
 */
class FirstExecutionLoopTest {
    
    @Test
    fun firstExecutionLoop_completes_without_crash_and_returns_traceable_result() {
        // Setup
        val projectId = "fel-test-project"
        val ledger = EventLedger()
        val repository = EventRepository(ledger)
        
        // Create RealContractorRegistry with three contractors
        val registry = RealContractorRegistry()
        
        // Verify registry has contractors
        val contractors = registry.getAll()
        assertEquals("Registry should have 3 contractors", 3, contractors.size)
        assertTrue("Should have OpenAI", contractors.any { it.contractorId == "openai-gpt4" })
        assertTrue("Should have Gemini", contractors.any { it.contractorId == "gemini-pro" })
        assertTrue("Should have Copilot", contractors.any { it.contractorId == "github-copilot" })
        
        // Step 1: Submit intent
        val intentPayload = mapOf(
            "intentId" to "test-intent-1",
            "objective" to "Implement user authentication system"
        )
        ledger.appendEvent(projectId, EventTypes.INTENT_SUBMITTED, intentPayload)
        
        // Step 2: Execute intent (derives contracts)
        val entryPoint = ExecutionEntryPoint(ledger)
        val authResult = entryPoint.executeIntent(projectId, intentPayload)
        
        assertTrue("ExecutionEntryPoint should authorize", authResult.authorized)
        assertNotNull("Should have event", authResult.event)
        assertEquals("Should be CONTRACTS_GENERATED", EventTypes.CONTRACTS_GENERATED, authResult.event?.type)
        
        // Verify contracts were generated
        val events = ledger.loadEvents(projectId)
        val contractsGenerated = events.firstOrNull { it.type == EventTypes.CONTRACTS_GENERATED }
        assertNotNull("CONTRACTS_GENERATED event should exist", contractsGenerated)
        
        val reportId = contractsGenerated?.payload?.get("report_id") as? String
        assertNotNull("Should have report_id", reportId)
        
        val contracts = contractsGenerated?.payload?.get("contracts") as? List<*>
        assertNotNull("Should have contracts", contracts)
        assertTrue("Should have at least one contract", contracts?.isNotEmpty() == true)
        
        // Step 3: Governor progression
        val governor = Governor(repository, null)
        
        // Advance from CONTRACTS_GENERATED -> CONTRACTS_READY
        var result = governor.runGovernor(projectId)
        assertEquals("Should advance to CONTRACTS_READY", Governor.GovernorResult.ADVANCED, result)
        
        // Advance from CONTRACTS_READY -> CONTRACT_STARTED
        result = governor.runGovernor(projectId)
        assertEquals("Should advance to CONTRACT_STARTED", Governor.GovernorResult.ADVANCED, result)
        
        // Advance from CONTRACT_STARTED -> TASK_ASSIGNED
        result = governor.runGovernor(projectId)
        assertEquals("Should advance to TASK_ASSIGNED", Governor.GovernorResult.ADVANCED, result)
        
        // Step 4: Verify TASK_ASSIGNED event exists
        val updatedEvents = ledger.loadEvents(projectId)
        val taskAssigned = updatedEvents.firstOrNull { 
            it.type == EventTypes.TASK_ASSIGNED && 
            it.payload["position"]?.let { pos -> 
                when (pos) {
                    is Int -> pos == 1
                    is Double -> pos.toInt() == 1
                    else -> false
                }
            } ?: false
        }
        assertNotNull("TASK_ASSIGNED event should exist for position 1", taskAssigned)
        
        val taskId = taskAssigned?.payload?.get("taskId") as? String
        assertNotNull("Should have taskId", taskId)
        
        // Step 5: Contractor selection and invocation (proper flow)
        // Extract contract details from CONTRACTS_GENERATED event
        val position = when (val pos = taskAssigned?.payload?.get("position")) {
            is Int -> pos
            is Double -> pos.toInt()
            else -> 1
        }
        
        val contractData = contracts?.firstOrNull { contract ->
            (contract as? Map<*, *>)?.get("position")?.let { p ->
                when (p) {
                    is Int -> p == position
                    is Double -> p.toInt() == position
                    else -> false
                }
            } ?: false
        } as? Map<*, *>
        
        val contractId = contractData?.get("contractId") as? String
        assertNotNull("Should have contractId", contractId)
        
        // Build ExecutionContract for ContractorsModule
        val executionContract = ExecutionContract(
            contractId = contractId!!,
            reportReference = reportId!!,
            position = position.toString()
        )
        
        // Define contract requirements
        val requirements = listOf(
            ContractRequirement("code_generation", 3, 1.0),
            ContractRequirement("reasoning", 2, 0.5)
        )
        
        // Step 5a: Contractor selection via ContractorsModule
        val matchingEngine = DeterministicMatchingEngine()
        val taskContract = matchingEngine.resolve(
            contract = executionContract,
            requirements = requirements,
            registry = registry
        )
        
        // Step 5b: Contractor invocation via ContractorInvocationLayer
        val contractPayload = mapOf(
            "taskId" to taskId!!,
            "contractId" to contractId,
            "position" to position,
            "reportReference" to reportId,
            "objective" to "Execute contract $contractId at position $position"
        )
        
        val invocationLayer = ContractorInvocationLayer()
        val contractorResult = invocationLayer.invoke(taskContract, contractPayload)
        
        // Verify contractor was invoked successfully
        assertEquals("Execution should succeed", "success", contractorResult.status)
        assertNotNull("Should have contractor_id", contractorResult.contractor_id)
        assertTrue("Contractor should be one of the three", 
            contractorResult.contractor_id in listOf("openai-gpt4", "gemini-pro", "github-copilot"))
        
        // Step 6: Verify result traceability
        assertNotNull("Should have contract_id", contractorResult.contract_id)
        assertTrue("contract_id should not be empty", contractorResult.contract_id.isNotEmpty())
        
        assertNotNull("Should have report_reference", contractorResult.report_reference)
        assertEquals("report_reference should match", reportId, contractorResult.report_reference)
        
        // Verify output structure
        val output = contractorResult.output as? Map<*, *>
        assertNotNull("Output should be a map", output)
        
        assertTrue("Output should indicate contractor was invoked", 
            output?.get("contractor_invoked") == true)
        assertEquals("Output should contain correct contractor_id",
            contractorResult.contractor_id, output?.get("contractor_id"))
        assertEquals("Output should contain correct contract_id",
            contractorResult.contract_id, output?.get("contract_id"))
        assertEquals("Output should contain correct report_reference",
            contractorResult.report_reference, output?.get("report_reference"))
        
        // Verify execution trace is present
        val executionTrace = output?.get("execution_trace") as? Map<*, *>
        assertNotNull("Should have execution trace", executionTrace)
        assertNotNull("Trace should have evaluated contractors", executionTrace?.get("evaluated"))
        assertNotNull("Trace should have matched contractors", executionTrace?.get("matched"))
        assertNotNull("Trace should have rejected contractors", executionTrace?.get("rejected"))
    }
    
    @Test
    fun contractorSelection_uses_DeterministicMatchingEngine_correctly() {
        val registry = RealContractorRegistry()
        val engine = DeterministicMatchingEngine()
        
        val contract = ExecutionContract(
            contractId = "contract_1",
            reportReference = "rrid-123",
            position = "1"
        )
        
        val requirements = listOf(
            ContractRequirement("code_generation", 4, 1.0),
            ContractRequirement("reasoning", 3, 0.8)
        )
        
        val taskContract = engine.resolve(contract, requirements, registry)
        
        assertEquals("Should match contract_id", "contract_1", taskContract.contractId)
        assertEquals("Should match report_reference", "rrid-123", taskContract.reportReference)
        assertEquals("Should match position", "1", taskContract.position)
        assertNotNull("Should have assignment", taskContract.assignment)
        assertEquals("Should be MATCHED mode", AssignmentMode.MATCHED, taskContract.assignment.mode)
        assertTrue("Should have assigned contractor", taskContract.assignment.contractorIds.isNotEmpty())
        
        // Verify trace
        assertNotNull("Should have trace", taskContract.trace)
        assertEquals("Should have evaluated 3 contractors", 3, taskContract.trace.evaluated.size)
        assertTrue("Should have matched 1 contractor", taskContract.trace.matched.isNotEmpty())
    }
    
    @Test
    fun contractorInvocationLayer_returns_structured_result() {
        val invocationLayer = ContractorInvocationLayer()
        
        val taskContract = TaskAssignedContract(
            contractId = "contract_1",
            reportReference = "rrid-456",
            position = "1",
            assignment = Assignment(
                contractorIds = listOf("openai-gpt4"),
                mode = AssignmentMode.MATCHED
            ),
            trace = ResolutionTrace(
                evaluated = listOf("openai-gpt4", "gemini-pro", "github-copilot"),
                matched = listOf("openai-gpt4"),
                rejected = emptyList()
            )
        )
        
        val payload = mapOf(
            "taskId" to "contract_1-step1",
            "objective" to "Test invocation"
        )
        
        val result = invocationLayer.invoke(taskContract, payload)
        
        assertEquals("Should be success", "success", result.status)
        assertEquals("Should match contract_id", "contract_1", result.contract_id)
        assertEquals("Should match report_reference", "rrid-456", result.report_reference)
        assertEquals("Should be openai-gpt4", "openai-gpt4", result.contractor_id)
        
        val output = result.output as Map<*, *>
        assertTrue("Output should indicate contractor invoked", output["contractor_invoked"] == true)
    }
    
    @Test
    fun firstExecutionLoop_handles_blocked_assignment_correctly() {
        val invocationLayer = ContractorInvocationLayer()
        
        val blockedContract = TaskAssignedContract(
            contractId = "contract_1",
            reportReference = "rrid-789",
            position = "1",
            assignment = Assignment(
                contractorIds = emptyList(),
                mode = AssignmentMode.BLOCKED
            ),
            trace = ResolutionTrace(
                evaluated = emptyList(),
                matched = emptyList(),
                rejected = emptyList()
            )
        )
        
        val result = invocationLayer.invoke(blockedContract, emptyMap())
        
        assertEquals("Should be failure", "failure", result.status)
        assertEquals("Contractor should be none", "none", result.contractor_id)
        assertNotNull("Should have error", result.error)
    }
}
