# SYSTEM_SURFACE_REPORT_V2.md

## BUILD STATUS

**FIXED** — `LedgerAudit.kt` previously referenced `Governor.VALID_TRANSITIONS` (an
unresolved reference because `Governor` lives in `com.agoii.mobile.governor`, outside
the `core` package). The dependency was removed by inlining an equivalent private map
inside `LedgerAudit` using only `EventTypes` constants. No Governor reference or
import remains in `core/`.

---

## SECTION 1 — ENTRY POINTS

### UI Triggers
| Entry point | File | Operation |
|---|---|---|
| User submits intent text | `ui/ProjectScreen.kt` | `bridge.submitIntent(projectId, text)` |
| User taps RUN STEP | `ui/ProjectScreen.kt` | `bridge.runGovernorStep(projectId)` |
| User taps APPROVE | `ui/ProjectScreen.kt` | `bridge.approveContracts(projectId)` |
| Screen initial load | `ui/ProjectScreen.kt` | `bridge.loadEvents / replayState / auditLedger / verifyReplay` (read-only) |

### Bridge Calls (CoreBridge)
`bridge/CoreBridge.kt` is the sole adapter between the UI layer and all core modules.
Every UI action calls exactly one bridge method. The bridge delegates to:
- `Governor` — for all state-advancing mutations
- `LedgerAudit`, `Replay`, `ReplayTest` — read-only operations
- `IrsOrchestrator` — IRS session management

### Non-UI Triggers
None detected. No background threads, no scheduled jobs, no external listeners.

---

## SECTION 2 — OUTPUT PIPELINE

**VERIFIED: PASS**

All user-visible output flows through:

```
InteractionEngine → InteractionFormatter → InteractionResult → UI
```

Specifically:
- `ProjectScreen.kt` calls `InteractionEngine.execute(contract, InteractionInput.LedgerInput(state))`
- `InteractionEngine` delegates extraction to `InteractionMapper` and formatting to `InteractionFormatter`
- The formatted `InteractionResult.content` string is rendered in the UI
- For simulation output: `SimulationEngine → SimulationView → InteractionInput.SimulationInput → InteractionEngine → InteractionFormatter → UI`

No raw state is rendered directly. No formatting occurs inside the UI layer. No bypass of `InteractionFormatter` exists.

---

## SECTION 3 — DEPENDENCY GRAPH

**CORE (`com.agoii.mobile.core`)**
- `Event.kt`, `EventRepository.kt`, `EventStore.kt`, `EventTypes.kt`, `LedgerAudit.kt`, `Replay.kt`, `ReplayTest.kt`
- Imports: **ZERO module imports** ✅
- After the build fix, `LedgerAudit.kt` no longer imports `Governor`

**GOVERNOR (`com.agoii.mobile.governor`)**
- `Governor.kt`, `SystemVerificationContract.kt`
- Location: `governor/` package — NOT inside `core/` ✅
- Depends on: `core`, `assembly`, `contractor`, `decision`, `execution`, `governance`, `tasks` (outward only) ✅

**MODULES**
| Module | Depends on |
|---|---|
| `bridge` | `core`, `governor`, `execution`, `irs` |
| `execution` | `core` only (EventRepository, EventTypes) |
| `contracts` | `core` (via governance models) |
| `contractor` | `core` |
| `tasks` | `core` |
| `simulation` | `core` (ReplayStructuralState only) |
| `interaction` | `core` (ReplayStructuralState), `simulation` (SimulationView) |
| `ingress` | none (pure model layer) |
| `irs` | `core`, `irs` sub-packages |
| `governance` | `core` |
| `assembly` | `core` |
| `decision` | `core`, `contractor` |

**VERDICT:** No reverse dependencies detected. Core has zero module imports. ✅

---

## SECTION 4 — MUTATION AUTHORITY

**VERDICT: PASS**

| Caller | Calls appendEvent? | Status |
|---|---|---|
| `governor/Governor.kt` | ✅ Yes — sole mutation authority | COMPLIANT |
| `bridge/CoreBridge.kt` | ❌ No direct writes | COMPLIANT |
| Any other module | ❌ No | COMPLIANT |

`ExecutionEventEmitter` has been deleted. All `appendEvent` calls reside exclusively inside `governor/Governor.kt`.

---

## SECTION 5 — PIPELINE FLOW

### LEDGER FLOW

```
UI (ProjectScreen)
  → CoreBridge.runGovernorStep / submitIntent / approveContracts
    → Governor.runGovernor / submitIntent / approveContracts
      → eventStore.appendEvent (ledger write)
      → Replay.replayStructuralState (read) → ReplayStructuralState
        → InteractionEngine.execute
          → InteractionMapper.extract → StateSlice
          → InteractionFormatter.format → String
            → InteractionResult → UI
```

✅ PASS — Full pipeline intact. No shortcuts.

### SIMULATION FLOW

```
SimulationEngine.simulate(ReplayStructuralState, SimulationContract)
  → SimulationMapper.map → SimulationSnapshot
  → SimulationAnalyzer.analyze → SimulationResult
  → SimulationEngine.toView → SimulationView
    → InteractionInput.SimulationInput
      → InteractionEngine.execute
        → InteractionMapper.extractFromSimulationView → StateSlice
        → InteractionFormatter.format → String
          → InteractionResult → UI
```

✅ PASS — Simulation passes through `InteractionEngine`. No bypass.

---

## SECTION 6 — CONTRACT VALIDATION

### IngressContract (`ingress/IngressContract.kt`)
- Pure data class — no logic, no mutation, no Android dependencies ✅
- Contains: `IngressContract`, `IntentType`, `Scope`, `References`, `Payload`, `ContractStatus`
- Independent: no imports from any module ✅

### InteractionContract (`interaction/InteractionContract.kt`)
- Pure data class — no logic, no mutation ✅
- Defines: `contractId`, `query`, `scope`, `outputType`, `sourceType`, `simulationId`
- Only defines query structure; no execution logic ✅

**VERDICT: PASS** ✅

---

## SECTION 7 — DUPLICATION CHECK

| Concern | Files | Status |
|---|---|---|
| Formatter | `interaction/InteractionFormatter.kt` — ONE formatter | ✅ PASS |
| Mapper | `interaction/InteractionMapper.kt` — ONE mapper for state extraction | ✅ PASS |
| Output pipeline | Single path: `InteractionEngine → InteractionFormatter → UI` | ✅ PASS |
| Simulation mapper | `simulation/SimulationMapper.kt` — scoped to simulation only, feeds interaction | ✅ PASS |

No UI duplication of formatting. No parallel output pipelines. No multiple mapper instances active.

**VERDICT: PASS** ✅

---

## SECTION 8 — UI COMPLIANCE

**File reviewed:** `ui/ProjectScreen.kt`

| Rule | Compliant? | Notes |
|---|---|---|
| Reads `ReplayStructuralState` | ✅ | Via `bridge.replayState()` |
| Uses `InteractionResult` | ✅ | Passes `InteractionResult.content` to composable |
| Triggers contracts via bridge | ✅ | All actions delegate to `CoreBridge` |
| No compute logic | ✅ | No arithmetic or state derivation |
| No formatting | ✅ | Only renders pre-formatted `content` strings |
| No validation | ✅ | No validation logic in UI |
| No system state interpretation | ✅ | Phase/status read from `InteractionResult`, not derived |

**VERDICT: PASS** ✅

---

## SECTION 9 — RISK SCAN

| Risk | Location | Severity | Description |
|---|---|---|---|
| **Unused bridge** | `interaction/SimulationInteractionBridge.kt` | LOW | `SimulationInteractionBridge.map()` is a pass-through that is never called. The `SimulationView` flows directly through `InteractionInput.SimulationInput`. Dead code but not a logic risk. |
| **ContractorEventEmitter** | `contractor/ContractorEventEmitter.kt` | NONE | Audited — no `appendEvent` calls. Does not violate mutation authority. |
| **TaskEventEmitter** | `tasks/TaskEventEmitter.kt` | NONE | Audited — no `appendEvent` calls. Does not violate mutation authority. |

---

## FINAL VERDICT

The build failure has been resolved: `LedgerAudit.kt` no longer references
`Governor` in any compilable form. Core package has zero module imports.

Mutation authority is fully enforced: `ExecutionEventEmitter.kt` has been deleted;
`appendEvent` is now called exclusively inside `governor/Governor.kt`.

SYSTEM_LOCKED_FOR_PROOF
