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
→ Assembly (output structuring)  
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

## 6. GOVERNOR ACTIVATION LAW

Governor is activated ONLY after a valid event exists in EventLedger.

RULES:

- Governor MUST NOT operate on raw input
- Governor MUST NOT operate on Intent Master
- Governor MUST ONLY consume persisted events
- Governor MUST NOT participate in intent construction
- Governor MUST NOT participate in contract derivation

---

## 7. CONTRACT ASSIGNMENT LAW

Contract-to-contractor assignment is governed exclusively by Governor.

RULES:

ONLY Governor may emit:

→ TASK_ASSIGNED

TASK_ASSIGNED binds:

- contract_id
- contractor_id
- position

PROHIBITED:

- Assignment inside ContractSystemOrchestrator
- Assignment inside CoreBridge
- Assignment inside Assembly

---

## 8. TASK EXECUTION LAW

Execution begins ONLY after TASK_ASSIGNED event exists.

RULES:

- No pre-execution
- No speculative execution
- No implicit execution
- Execution is strictly ledger-driven

---

## 9. CONTRACTORS LAW

Contractors are external contract executors.

RULES:

- Execute ONLY assigned execution contracts
- Operate strictly within contract scope
- Return structured outputs

Contractors MUST NOT:

- Write to EventLedger
- Modify system state
- Define contracts
- Bypass Execution Authority

All outputs MUST:

→ return to system  
→ pass Execution Authority  
→ be persisted via EventLedger  

---

## 10. EXTERNAL SYSTEM BOUNDARY LAW

External systems have ZERO authority.

RULES:

- May ONLY respond to contracts
- All responses are untrusted until validated
- Cannot influence state directly

---

## 11. ASSEMBLY LAW

Assembly is a deterministic output structuring layer.

RULES:

- Consumes ONLY completed contract outputs
- Produces structured system output
- Feeds ICS

Assembly MUST NOT:

- Execute contracts
- Call external systems
- Validate
- Write to ledger
- Introduce new data

---

## 12. BRIDGE LAW (CoreBridge)

CoreBridge is a thin adapter AND Execution Authority host.

RULES:

- May coordinate ContractSystemOrchestrator
- Must execute Validation + Authorization
- Must delegate to Governor

CoreBridge MUST NOT:

- Bypass Execution Authority
- Write outside EventLedger
- Interpret outputs
- Define contracts
- Introduce hidden logic

---

## 13. ICS LAW (INTERACTION CONTRACT SYSTEM)

ICS is contract-bound output translation.

RULES:

- Executes communication contracts ONLY
- Translates structured output → human language
- No validation
- No mutation
- No execution authority

ICS MUST NOT:

- Generate logic
- Invent responses
- Operate outside contract scope

---

## 14. INGRESS LAW

IngressContract is contract-bound input translation.

RULES:

- Converts human input → structured data
- Must follow communication contract
- No validation
- No execution authority
- No ledger writes

---

## 15. CONTRACT SYSTEM LAW (GLOBAL)

ALL CONTRACTS originate from Contract System.

CONTRACT TYPES:

1. Communication Contracts  
2. Execution Contracts  
3. Validation Contracts  
4. Recovery Contracts  
5. Simulation Contracts  

RULES:

- No module may invent contracts
- No inline contract definitions
- All contracts must be structured

---

## 16. AGENT EXECUTION LAW

AGENTS HAVE ZERO AUTHORITY

RULES:

- Execute ONLY contracts
- Cannot redefine scope
- Cannot decide next steps
- Cannot write to ledger

---

## 17. FAILURE LAW

FAILURE = CONTRACT NOT SATISFIED

RESPONSE:

→ ISSUE NEW CONTRACT

NO:

- patching  
- guessing  
- silent continuation  

---

## 18. RECOVERY LAW

Recovery is contract-driven.

FLOW:

Failure  
→ Recovery Contract  
→ Execution  
→ Evaluation  

---

## 19. SIMULATION LAW

Simulation is NON-AUTHORITATIVE.

RULES:

- Executes contracts in simulation mode
- Cannot write to ledger
- Cannot advance state
- Produces predictions only

---

## 20. INTENT MODULE LAW

Intent Module is contract-driven.

RULES:

- Issues communication contracts
- Evaluates responses
- Repeats until complete

OUTPUT:

→ Intent Master (in-memory ONLY)

---

## 21. COMMUNICATION LAW

ALL COMMUNICATION IS CONTRACT EXECUTION

RULES:

- No free-form interaction
- No uncontrolled prompting
- No interpretation outside contract

---

## 22. PAYLOAD SCHEMA LAW (LOCKED)

[UNCHANGED — preserved exactly]

---

## 23. DEAD CODE LAW

[UNCHANGED]

---

## 24. SYSTEM INVARIANTS

[UNCHANGED + EXTENDED CONTRACT-BOUND SYSTEM]

---

## 25. ENFORCEMENT

IF ANY MODULE EXECUTES WITHOUT CONTRACT:

→ BLOCKED: CONTRACT VIOLATION

---

END OF DOCUMENT
