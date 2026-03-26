# AGOII ARCHITECTURE — MASTER LAW (FULLY ALIGNED & LOCKED)

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
→ Task Assignment (contract → contractor binding)  
→ Contractor Execution  
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

1. Validation  
2. Authorization  

RULES:
- Validation success ≠ authorization
- Both MUST pass before write
- If either fails → BLOCK

MANDATORY ENFORCEMENT:

- Contracts list is non-empty
- Each contract contains id, name, position
- Positions are strictly sequential (1..N)
- total == contracts.size

---

## 4. CONTRACT DERIVATION LAW

Execution contracts MUST be derived BEFORE entering the ledger.

SOURCE OF TRUTH:
→ ContractSystemOrchestrator

RULES:
- Deterministic
- No hardcoding
- Governor MUST NOT derive contracts

---

## 5. GOVERNOR LAW

Governor is a deterministic state machine.

INPUT:
→ Event stream ONLY

OUTPUT:
→ Next event OR null

RULES:
- No derivation
- No execution logic
- No validation
- No external calls

MANDATORY:

On INTENT_SUBMITTED:
→ WAIT for CONTRACTS_GENERATED

---

## 6. GOVERNOR ACTIVATION LAW

Governor activates ONLY on persisted events.

- No raw input
- No intent memory usage
- No pre-ledger behavior

---

## 7. CONTRACT ASSIGNMENT LAW

ONLY Governor may assign contracts.

EVENT:
→ TASK_ASSIGNED

This binds:

- contract_id
- contractor_id
- position

---

## 8. TASK EXECUTION LAW

Execution begins ONLY after TASK_ASSIGNED.

RULES:
- Ledger-driven ONLY
- No speculative execution
- No implicit execution

---

## 9. CONTRACTORS LAW (CRITICAL)

Contractors are a governed execution pool.

CONTRACTORS MODULE MUST EXIST.

It MUST contain:

→ Contractor Registry  
→ Capability Profiles  
→ Reliability Metrics  
→ Constraint Definitions  

Contractors:

- Execute ONLY assigned contracts
- Operate strictly within scope
- Return structured outputs

PROHIBITED:

- Writing to EventLedger
- Defining contracts
- Modifying system state

---

## 10. CONTRACTOR REGISTRY LAW

ALL contractors MUST be registered.

Registry MUST define:

- capabilities
- constraints
- reliability score
- drift profile

RULE:

If not registered → contractor does not exist

---

## 11. DETERMINISTIC MATCHING LAW (CRITICAL)

Contract → Contractor selection MUST be computed.

NO:
- heuristic selection
- LLM choice
- implicit matching

MATCHING PROCESS:

1. Feasibility Check (binary)
   - capability match REQUIRED
   - constraint violations MUST be zero

2. Scoring Function

Fitness(contract, contractor) → [0..1]

Based on:

- capability coverage
- reliability probability
- constraint compliance
- drift penalty

3. Selection

- highest score wins
- deterministic outcome
- same input → same selection

---

## 12. MULTI-CONTRACTOR (SWARM) LAW

IF no single contractor satisfies contract:

→ system MUST construct a swarm

RULES:

- combined capability coverage = 100%
- no constraint conflicts
- deterministic composition

---

## 13. EXTERNAL SYSTEM BOUNDARY LAW

External systems:

- ZERO authority
- respond ONLY to contracts
- outputs are untrusted until validated

---

## 14. ASSEMBLY LAW

Assembly is deterministic.

- consumes completed outputs
- structures final result
- feeds ICS

NO:
- execution
- validation
- new data creation

---

## 15. ICS LAW

ICS executes communication contracts ONLY.

- translates structured output → human language
- no logic generation
- no mutation

---

## 16. INGRESS LAW

Ingress executes input contracts ONLY.

- translates human → structured input
- no validation
- no interpretation

---

## 17. CONTRACT SYSTEM LAW

ALL contracts originate from Contract System.

TYPES:

- Communication  
- Execution  
- Validation  
- Recovery  
- Simulation  
- Commit (real-world execution)

---

## 18. AGENT EXECUTION LAW

AGENTS HAVE ZERO AUTHORITY

- execute contracts only
- cannot decide
- cannot write
- cannot expand scope

---

## 19. FAILURE LAW

FAILURE = contract not satisfied

RESPONSE:

→ issue new contract

---

## 20. RECOVERY LAW

Recovery is contract-driven ONLY.

---

## 21. SIMULATION LAW

Simulation is NON-AUTHORITATIVE

- executes contracts
- cannot write
- cannot advance state

---

## 22. INTENT MODULE LAW

Intent Module:

- issues communication contracts
- gathers structured intent
- outputs Intent Master (in-memory ONLY)

---

## 23. COMMUNICATION LAW

ALL communication is contract-based.

NO free-form interaction allowed.

---

## 24. COMMIT EXECUTION LAW (NEW — CRITICAL)

Real-world execution REQUIRES:

→ User approval  
→ Commit Contract  

RULES:

- separates internal execution from real execution
- triggers external contractors
- must pass Execution Authority
- must be written to ledger

NO execution without commit contract.

---

## 25. PAYLOAD SCHEMA LAW

[UNCHANGED]

---

## 26. DEAD CODE LAW

[UNCHANGED]

---

## 27. SYSTEM INVARIANTS

- Append-only ledger  
- Deterministic replay  
- Single write authority  
- Contract-bound execution  
- Deterministic contractor selection  
- No implicit capability  
- No agent autonomy  

---

## 28. ENFORCEMENT

IF ANY MODULE:

- executes without contract  
- selects contractor without matching  
- bypasses registry  

→ BLOCKED: ARCHITECTURAL VIOLATION  

---

END OF DOCUMENT
