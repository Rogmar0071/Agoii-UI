# AGOII ARCHITECTURE — MASTER LAW (LOCKED)  

STATUS: AUTHORITATIVE  
MODE: ENFORCED  
MUTABILITY: RESTRICTED (EXPLICIT CONTRACT REQUIRED)  

---  

## 0. SYSTEM IDENTITY  

Agoii is a deterministic orchestration system.  

- All system change originates from events  
- All events are validated before persistence  
- No module may bypass the event pipeline  
- No module may assume authority  

---  

## 1. CORE FLOW (NON-NEGOTIABLE)  

RAW INPUT    
→ IngressContract (translation only)    
→ Intent Module (intent completion)    
→ ContractSystemOrchestrator (derivation)    
→ Execution Authority (validation + authorization)    
→ EventLedger.appendEvent()    
→ Governor (state progression)    
→ ICS (output only)  

---  

## 2. SINGLE WRITE AUTHORITY LAW  

ONLY ONE COMPONENT MAY WRITE:  

→ EventLedger  

RULES:  
- No direct EventStore writes  
- No indirect persistence bypass  
- No module may simulate a write  

VIOLATION = SYSTEM BREACH  

---  

## 3. EXECUTION AUTHORITY LAW  

Execution Authority is the ONLY gate before ledger writes.  

Execution Authority consists of:  

1. Validation (structural + invariant)  
2. Authorization (explicit approval of transition)  

RULES:  
- Validation success ≠ authorization  
- Both MUST pass before write  
- If either fails → BLOCK  

MANDATORY ENFORCEMENT:  

Execution Authority MUST enforce:  

- Contracts list is non-empty  
- All contracts contain id, name, position  
- Positions are strictly sequential (1..N)  
- total == contracts.size  

Failure at any stage MUST block ledger write.  

IMPLEMENTATION NOTE:  
Validation and Authorization may be co-located,  
but MUST remain logically separable phases.  

---  

## 4. CONTRACT DERIVATION LAW  

Contracts MUST be derived BEFORE entering the ledger.  

SOURCE OF TRUTH:  
→ ContractSystemOrchestrator  

MAPPING RULE (LOCKED):  

ExecutionPlan.steps → CONTRACTS_GENERATED.payload["contracts"]  

Each step MUST map as:  

- id = "contract_{position}"  
- name = step.description  
- position = step.position  

Payload MUST include:  
- contracts: List<Map>  
- total: Int  

RULES:  
- No hardcoded contracts  
- Governor is strictly prohibited from deriving contracts  
- Deterministic: same intent → same contracts  

ENFORCEMENT:  
Contract derivation MUST occur before any ledger write.  

---  

## 5. GOVERNOR LAW  

Governor is a deterministic state machine.  

INPUT:  
→ Event stream ONLY  

OUTPUT:  
→ Next event OR null  

RULES:  
- Pure: no external calls  
- Deterministic: ledger-driven only  
- No contract derivation  
- No execution logic  
- No validation logic  
- No side effects  

Governor MUST:  
- Consume ledger state  
- Enforce transition rules  
- Never introduce new information  

MANDATORY BEHAVIOR:  

On INTENT_SUBMITTED:  
- Governor MUST NOT produce CONTRACTS_GENERATED  
- Governor MUST wait until CONTRACTS_GENERATED exists in ledger  

---  

## 6. BRIDGE LAW (CoreBridge)  

CoreBridge is a thin adapter AND Execution Authority host.  

RULES:  
- May coordinate ContractSystemOrchestrator  
- May execute Validation + Authorization phases  
- MUST NOT bypass Execution Authority rules  
- MUST NOT write outside EventLedger  
- MUST NOT contain hidden decision layers  

CoreBridge responsibilities are LIMITED to:  
- Derivation coordination (pre-ledger)  
- Execution Authority enforcement  
- Delegation to Governor  

VIOLATION = ARCHITECTURAL DRIFT  

---  

## 7. ICS LAW (INTERACTION LAYER)  

ICS is output-only.  

RULES:  
- No validation  
- No mutation  
- No execution  
- No state authority  

Purpose:  
→ Translate system state to user communication  

---  

## 8. INGRESS LAW  

IngressContract transforms input ONLY.  

RULES:  
- No validation authority  
- No execution authority  
- No ledger writes  

Purpose:  
→ Human → structured input  

---  

## 9. PAYLOAD SCHEMA LAW (LOCKED)  

All event payloads MUST be explicitly defined.  

### CONTRACTS_GENERATED (MANDATORY STRUCTURE)  

{  
  "contracts": [  
    {  
      "id": String,  
      "name": String,  
      "position": Int  
    }  
  ],  
  "total": Int  
}  

CONSTRAINTS (MANDATORY):  
- positions MUST be sequential (1..N)  
- total MUST equal contracts.size  

### TASK_ASSIGNED (MANDATORY STRUCTURE)  

{  
  "contractorId": String,  
  "taskId": String,  
  "position": Int,  
  "total": Int  
}  

RULES:  
- No implicit fields  
- No silent schema changes  
- ValidationLayer MUST enforce this structure  

---  

## 10. DEAD CODE LAW  

Authority-related components MUST NOT exist unused.  

RULES:  
- Unused authority logic MUST be removed OR explicitly disabled  
- No parallel execution paths allowed  
- No shadow orchestrators  

---  

## 11. SIMULATION LAW  

Simulation is NON-AUTHORITATIVE.  

RULES:  
- Cannot trigger events  
- Cannot advance state  
- Cannot override execution  

Purpose:  
→ Analysis only  

---  

## 12. FAILURE LAW  

IF ANY STEP FAILS:  

→ SYSTEM BLOCKS  

NO:  
- fallback execution  
- silent correction  
- assumption-based continuation  

---  

## 13. SYSTEM INVARIANTS  

- Append-only ledger  
- Deterministic replay  
- Single write authority  
- Explicit validation + authorization  
- No hidden mutation  
- No implicit state transitions  
- Sequential contract execution integrity  
- Payload truth must match structural reality  

---  

## 14. ENFORCEMENT  

ALL CONTRACTS MUST:  

- Reference this document  
- Conform to all laws  
- Refuse execution on ambiguity  

---  

END OF DOCUMENT
