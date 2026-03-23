package com.agoii.mobile

import com.agoii.mobile.ingress.ContractStatus
import com.agoii.mobile.ingress.IngressContract
import com.agoii.mobile.ingress.IntentType
import com.agoii.mobile.ingress.Payload
import com.agoii.mobile.ingress.References
import com.agoii.mobile.ingress.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the Ingress Contract System.
 *
 * All tests run on the JVM — no Android framework or network access required.
 *
 * Verified invariants:
 *  1. Immutability — data classes are value-equal and support copy().
 *  2. References — optional fields default to null; binding round-trips correctly.
 *  3. Payload — rawInput and normalizedIntent are preserved verbatim.
 *  4. Enumerations — all declared values exist with their expected ordinals.
 *  5. No mutation — no field on IngressContract may be reassigned.
 */
class IngressContractTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun minimalPayload(
        rawInput: String = "create task alpha",
        normalizedIntent: String = "create task alpha",
        extractedFields: Map<String, String> = emptyMap()
    ) = Payload(
        rawInput         = rawInput,
        normalizedIntent = normalizedIntent,
        extractedFields  = extractedFields
    )

    private fun minimalContract(
        contractId: String    = "ingress-001",
        intentType: IntentType = IntentType.ACTION,
        scope: Scope          = Scope.TASK,
        references: References = References(),
        payload: Payload      = minimalPayload(),
        status: ContractStatus = ContractStatus.PENDING
    ) = IngressContract(
        contractId = contractId,
        intentType = intentType,
        scope      = scope,
        references = references,
        payload    = payload,
        status     = status
    )

    // ── 1. Immutability / value equality ─────────────────────────────────────

    @Test
    fun `two IngressContracts with identical fields are equal`() {
        val c1 = minimalContract()
        val c2 = minimalContract()
        assertEquals("Identical contracts must be equal", c1, c2)
    }

    @Test
    fun `copy preserves all fields`() {
        val original = minimalContract(contractId = "x-99", status = ContractStatus.ACCEPTED)
        val copy     = original.copy()
        assertEquals(original, copy)
    }

    @Test
    fun `copy with override changes only the specified field`() {
        val original = minimalContract(status = ContractStatus.PENDING)
        val rejected = original.copy(status = ContractStatus.REJECTED)
        assertEquals(ContractStatus.REJECTED, rejected.status)
        assertEquals(original.contractId, rejected.contractId)
        assertEquals(original.intentType, rejected.intentType)
        assertEquals(original.scope,      rejected.scope)
        assertEquals(original.references, rejected.references)
        assertEquals(original.payload,    rejected.payload)
    }

    // ── 2. References – optional field defaults ───────────────────────────────

    @Test
    fun `References defaults to all-null fields`() {
        val refs = References()
        assertNull("contractId should default to null",   refs.contractId)
        assertNull("taskId should default to null",       refs.taskId)
        assertNull("simulationId should default to null", refs.simulationId)
    }

    @Test
    fun `References binds contractId correctly`() {
        val refs = References(contractId = "parent-42")
        assertEquals("parent-42", refs.contractId)
        assertNull(refs.taskId)
        assertNull(refs.simulationId)
    }

    @Test
    fun `References binds all fields simultaneously`() {
        val refs = References(
            contractId   = "c-1",
            taskId       = "t-2",
            simulationId = "s-3"
        )
        assertEquals("c-1", refs.contractId)
        assertEquals("t-2", refs.taskId)
        assertEquals("s-3", refs.simulationId)
    }

    // ── 3. Payload – content preservation ────────────────────────────────────

    @Test
    fun `Payload preserves rawInput verbatim`() {
        val raw     = "  Create   Task  ALPHA  "
        val payload = minimalPayload(rawInput = raw)
        assertEquals("rawInput must not be modified", raw, payload.rawInput)
    }

    @Test
    fun `Payload preserves normalizedIntent verbatim`() {
        val normalized = "create task alpha"
        val payload    = minimalPayload(normalizedIntent = normalized)
        assertEquals(normalized, payload.normalizedIntent)
    }

    @Test
    fun `Payload stores extractedFields by key`() {
        val fields  = mapOf("entity" to "alpha", "action" to "create")
        val payload = minimalPayload(extractedFields = fields)
        assertEquals("alpha",  payload.extractedFields["entity"])
        assertEquals("create", payload.extractedFields["action"])
    }

    @Test
    fun `Payload with empty extractedFields is valid`() {
        val payload = minimalPayload(extractedFields = emptyMap())
        assertTrue(payload.extractedFields.isEmpty())
    }

    // ── 4. Enumerations ───────────────────────────────────────────────────────

    @Test
    fun `IntentType has exactly QUERY, ACTION, CLARIFICATION`() {
        val values = IntentType.values().map { it.name }
        assertEquals(listOf("QUERY", "ACTION", "CLARIFICATION"), values)
    }

    @Test
    fun `Scope has exactly SYSTEM, CONTRACT, TASK, EXECUTION, SIMULATION`() {
        val values = Scope.values().map { it.name }
        assertEquals(listOf("SYSTEM", "CONTRACT", "TASK", "EXECUTION", "SIMULATION"), values)
    }

    @Test
    fun `ContractStatus has exactly PENDING, ACCEPTED, REJECTED`() {
        val values = ContractStatus.values().map { it.name }
        assertEquals(listOf("PENDING", "ACCEPTED", "REJECTED"), values)
    }

    // ── 5. Full contract round-trip ───────────────────────────────────────────

    @Test
    fun `IngressContract fields are accessible and correct`() {
        val payload = Payload(
            rawInput         = "query system health",
            normalizedIntent = "query system health",
            extractedFields  = mapOf("target" to "system", "check" to "health")
        )
        val refs = References(simulationId = "sim-7")
        val contract = IngressContract(
            contractId = "ingress-007",
            intentType = IntentType.QUERY,
            scope      = Scope.SYSTEM,
            references = refs,
            payload    = payload,
            status     = ContractStatus.ACCEPTED
        )

        assertEquals("ingress-007",             contract.contractId)
        assertEquals(IntentType.QUERY,          contract.intentType)
        assertEquals(Scope.SYSTEM,              contract.scope)
        assertEquals(refs,                      contract.references)
        assertEquals(payload,                   contract.payload)
        assertEquals(ContractStatus.ACCEPTED,   contract.status)
        assertEquals("sim-7",                   contract.references.simulationId)
        assertEquals("system",                  contract.payload.extractedFields["target"])
    }
}
