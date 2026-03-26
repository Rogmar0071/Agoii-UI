# AGOII ARCHITECTURE — MASTER LAW (ALIGNED & LOCKED)

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

MANDATORY EXTENSION:

ALL EXECUTION IN AGOII IS CONTRACT-BOUND

- No agent may act outside a contract
- No module may execute logic without issuing or evaluating a contract
- No communication may occur without a communication contract

---

## 1. CORE FLOW (NON-NEGOTIABLE)

RAW INPUT  
→ IngressContract (translation only)  
→ Intent Module (contract-driven intent completion)  
→ ContractSystemOrchestrator (derivation ONLY)  
→ Execution Authority (validation + authorization)  
→ EventLedger.appendEvent()  
→ Governor (state progression)  
→ ICS (contract-based output only)  

MANDATORY:

- All modules operate via contracts
- No direct execution without contract issuance

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

Execution Authority MUST validate CONTRACT OUTPUT:

- Contracts list is non-empty
- All contracts contain id, name, position
- Positions are strictly sequential (1..N)
- total == contracts.size

Failure at any stage MUST block ledger write.

---

## 4. CONTRACT DERIVATION LAW

Execution contracts MUST be derived BEFORE entering the ledger.

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

IMPORTANT:

ContractSystemOrchestrator ONLY derives EXECUTION CONTRACTS

It MUST NOT:
- Generate communication contracts
- Generate recovery contracts
- Generate validation contracts

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

MANDATORY:

On INTENT_SUBMITTED:
→ Governor MUST NOT produce CONTRACTS_GENERATED  
→ Governor MUST wait for CONTRACTS_GENERATED in ledger  

---

## 6. BRIDGE LAW (CoreBridge)

CoreBridge is a thin adapter AND Execution Authority host.

RULES:
- May coordinate ContractSystemOrchestrator
- May trigger contract execution
- Must execute Validation + Authorization phases
- Must delegate to Governor

CoreBridge MUST NOT:
- Bypass Execution Authority
- Write outside EventLedger
- Interpret agent output
- Define contract logic
- Introduce hidden decision layers

VIOLATION = ARCHITECTURAL DRIFT

---

## 7. ICS LAW (INTERACTION CONTRACT SYSTEM)

ICS is contract-bound output translation.

RULES:
- Executes communication contracts ONLY
- Translates structured system output → human language
- No validation
- No mutation
- No execution authority
- No state authority

ICS MUST NOT:
- Generate logic
- Invent responses
- Interpret outside contract scope

---

## 8. INGRESS LAW

IngressContract is contract-bound input translation.

RULES:
- Converts human input → structured data
- Must follow communication contract
- No validation authority
- No execution authority
- No ledger writes

Ingress MUST NOT:
- Inject meaning
- Interpret intent
- Perform completion

---

## 9. CONTRACT SYSTEM LAW (GLOBAL)

ALL CONTRACTS MUST BE GENERATED AND MANAGED BY CONTRACT SYSTEM

CONTRACT TYPES:

1. Communication Contracts (AI ↔ User)
2. Execution Contracts (Contractors)
3. Validation Contracts
4. Recovery Contracts
5. Simulation Contracts

RULES:

- No module may invent contracts
- No inline contract definitions
- All contracts are structured and governed
- Contracts define:
  - objective
  - scope
  - constraints
  - expected output
  - completion criteria

---

## 10. AGENT EXECUTION LAW

AGENTS HAVE ZERO AUTHORITY

AGENTS ONLY EXECUTE CONTRACTS

APPLIES TO:
- AI (communication)
- Contractors
- Internal processors

RULES:
- Agents cannot redefine scope
- Agents cannot decide next steps
- Agents cannot write to ledger

VIOLATION = SYSTEM BREACH

---

## 11. FAILURE LAW

FAILURE = CONTRACT NOT SATISFIED

RESPONSE:

→ ISSUE NEW CONTRACT

RULES:
- No patching
- No assumption-based continuation
- No silent correction

SYSTEM BLOCKS ONLY IF:

→ No valid contract can be issued

---

## 12. RECOVERY LAW

RECOVERY IS CONTRACT-BASED

RULES:

- No state mutation
- No direct correction
- No override logic

RECOVERY FLOW:

Failure detected  
→ Recovery Contract generated  
→ Executed  
→ Evaluated  

---

## 13. SIMULATION LAW

Simulation is NON-AUTHORITATIVE but CONTRACT-DRIVEN

RULES:

- Executes contracts in simulation mode
- Cannot write to ledger
- Cannot advance real state
- Produces predicted outputs

---

## 14. INTENT MODULE LAW

Intent Module is contract-driven.

RULES:

- Does NOT complete intent directly
- Issues communication contracts to complete intent
- Evaluates responses
- Re-issues contracts until completion

FLOW:

Missing data  
→ Communication Contract  
→ User response  
→ Evaluate  
→ Repeat until complete  

---

## 15. COMMUNICATION LAW

ALL COMMUNICATION IS CONTRACT EXECUTION

RULES:

- No free-form AI interaction
- No uncontrolled prompting
- No interpretation outside contract

ALL user interaction MUST:
→ originate from a communication contract  
→ return structured output  

---

## 16. PAYLOAD SCHEMA LAW (LOCKED)

All event payloads MUST be explicitly defined.

### CONTRACTS_GENERATED

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

CONSTRAINTS:
- positions MUST be sequential (1..N)
- total MUST equal contracts.size

### TASK_ASSIGNED

{
  "contractorId": String,
  "taskId": String,
  "position": Int,
  "total": Int
}

RULES:
- No implicit fields
- No silent schema changes
- ValidationLayer MUST enforce structure

---

## 17. DEAD CODE LAW

Authority-related components MUST NOT exist unused.

RULES:
- Unused authority logic MUST be removed OR explicitly disabled
- No parallel execution paths
- No shadow orchestrators

---

## 18. SYSTEM INVARIANTS

- Append-only ledger
- Deterministic replay
- Single write authority
- Explicit validation + authorization
- No hidden mutation
- No implicit transitions
- Sequential contract execution integrity
- Payload truth matches structural reality

EXTENDED:

- All execution is contract-bound
- All communication is contract-bound
- All recovery is contract-bound
- No agent autonomy

---

## 19. ENFORCEMENT

ALL CONTRACTS MUST:

- Reference this document
- Conform to all laws
- Refuse execution on ambiguity

MANDATORY:

IF ANY MODULE EXECUTES WITHOUT CONTRACT:

→ BLOCKED: CONTRACT VIOLATION

---

END OF DOCUMENT
