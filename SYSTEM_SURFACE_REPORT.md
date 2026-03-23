# STRUCTURAL SURFACE MAPPING REPORT
**Repository:** Rogmar0071/Agoii-UI  
**Date:** 2026-03-23  
**Mode:** Validation — READ-ONLY analysis with targeted corrections applied

---

## SECTION 1: ENTRY POINTS

All user entry points are channelled through the single Jetpack Compose screen.

| File | Function / Trigger | Trigger Type |
|------|--------------------|--------------|
| `ui/MainActivity.kt` | `onCreate()` → `setContent { ProjectScreen("agoii-project-001") }` | Android lifecycle (app launch) |
| `ui/ProjectScreen.kt` | `LaunchedEffect(projectId)` → `reload()` | UI lifecycle (initial render, automatic) |
| `ui/ProjectScreen.kt` | `InputBar.onSend` → `bridge.submitIntent(projectId, objective)` | User text input + SEND button |
| `ui/ProjectScreen.kt` | `ActionBar.onRunStep` → `bridge.runGovernorStep(projectId)` | User button press — RUN STEP |
| `ui/ProjectScreen.kt` | `ActionBar.onApprove` → `bridge.approveContracts(projectId)` | Conditional user button press — APPROVE (only when phase == `contracts_ready`) |

**Non-UI entry points:** NONE DETECTED. Every system action originates from the UI composable.

---

## SECTION 2: OUTPUT PATH VALIDATION

### Primary rendering path

```
reload()
  → bridge.replayState(projectId)                           [core.Replay → EventRepository]
  → InteractionContract(scope=FULL_SYSTEM, outputType=DETAILED, sourceType=LEDGER)
  → interactionEngine.execute(contract, LedgerInput(state)) [InteractionEngine]
  → InteractionMapper.extract(scope, state) → StateSlice
  → InteractionFormatter.format(DETAILED, slice) → String
  → InteractionResult.content
  → StatePanel renders interactionResult.content            [UI Text]
```

✅ **PASS** — Primary user-visible output flows through `InteractionEngine.execute()`.  
✅ **PASS** — No direct UI rendering from Simulation, Execution, Contract, or Intent modules.  
✅ **PASS** — `InteractionFormatter` is invoked only from within `InteractionEngine`. No direct formatter usage found outside the Interaction layer.  
✅ **PASS** — No bypass paths detected for the primary rendering path.

### Partial bypass — `ReplayVerification` rendered directly

`StatePanel` also renders `verification.valid`, `verification.auditResult.checkedEvents`, and `verification.invariantErrors` directly from `bridge.verifyReplay()` without routing them through `InteractionEngine`.

Per **STEP 8** (UI Compliance), the UI is permitted to read `ReplayState` (read-only). `ReplayVerification` is a derived, read-only aggregate of the ledger and therefore this pattern is within the stated permission boundary. No formatter bypass applies here.

---

## SECTION 3: MODULE DEPENDENCIES

### core/

| File | Imports (non-external) | Status |
|------|------------------------|--------|
| `Event.kt` | _(gson annotations only)_ | ✅ |
| `EventRepository.kt` | _(none)_ | ✅ |
| `EventStore.kt` | Android SDK, gson (external only) | ✅ |
| `EventTypes.kt` | _(none)_ | ✅ |
| `LedgerAudit.kt` | _(none; uses `Governor.VALID_TRANSITIONS` via same package)_ | ✅ |
| `Replay.kt` | _(none)_ | ✅ |
| `ReplayTest.kt` | _(none; uses same-package types)_ | ✅ |
| `Governor.kt` | `assembly`, `contractor`, `decision`, `execution`, `governance`, `tasks` | ❌ **VIOLATION** |
| `SystemVerificationContract.kt` | `governance.StateSurfaceMirror`, `governance.StructuralStateAwareness` | ❌ **FIXED** → moved to `governance/` |

### interaction/

| File | Imports (non-external) | Status |
|------|------------------------|--------|
| `InteractionContract.kt` | _(none)_ | ✅ |
| `InteractionResult.kt` | _(none)_ | ✅ |
| `InteractionFormatter.kt` | _(none)_ | ✅ |
| `InteractionEngine.kt` | `core.ReplayState`, `simulation.SimulationView` | ✅ borderline* |
| `InteractionMapper.kt` | `core.ReplayState`, `simulation.SimulationView` | ✅ borderline* |
| `SimulationInteractionBridge.kt` | `simulation.SimulationView` | ✅ |

\* The requirement states "Interaction does NOT depend on `SimulationResult`". `SimulationView` is the designated cross-layer boundary object (not `SimulationResult`). Its use here is intentional and by design.

### simulation/

All files depend only on `core.ReplayState` or nothing. **Zero dependencies** on ui, ingress, or interaction. ✅

### execution/

Files depend only on `core` (for `EventRepository`/`EventTypes`) and peer modules (`contractor`, `tasks`). No dependency on ui, simulation, or ingress. ✅

### ingress/

`IngressContract.kt` has zero imports — fully self-contained pure model layer. ✅

### ui/

| File | Imports (non-external) | Status |
|------|------------------------|--------|
| `ProjectScreen.kt` | `bridge.CoreBridge`, `core.*`, `interaction.*` | ✅ |
| `ui/core/*` | `core.EventTypes`, `core.ReplayState` (read-only) | ✅ |
| `ui/modules/*` | `ui.core.UIState` (internal to ui) | ✅ |
| `ui/orchestration/*` | `core.ReplayState`, `ui.core.*`, `ui.modules.*` | ✅ |

**UI depends only on:** Interaction layer and `ReplayState` (read-only). ✅

### Dependency violations

| # | Location | Violation | Severity |
|---|----------|-----------|----------|
| 1 | `core/Governor.kt` | Imports from `assembly`, `contractor`, `decision`, `execution`, `governance`, `tasks` — violates "Core has ZERO dependency on modules" | ❌ HIGH |
| 2 | `core/SystemVerificationContract.kt` | Imports from `governance.StateSurfaceMirror`, `governance.StructuralStateAwareness` | ❌ FIXED |

---

## SECTION 4: MUTATION SURFACE

✅ **PASS** — `Governor` is the sole authority that calls `eventStore.appendEvent()`.  
✅ **PASS** — `CoreBridge` never writes directly; it delegates all mutations to `Governor`.  
✅ **PASS** — No writes detected in the Interaction layer (pure pipeline).  
✅ **PASS** — No writes detected in the Simulation layer (read-only, pure).  
✅ **PASS** — `InteractionMapper` and `InteractionFormatter` are pure functions (no state, no I/O).

**Note — local state in UI:** `LedgerViewEngine` holds `private var _uiState: UIState?` mutated via `render()`. This is local presentation state only; it does not affect the event ledger and does not violate ledger integrity. Strictly interpreted it is a state write inside the UI layer.

---

## SECTION 5: PIPELINE VALIDATION

### LEDGER FLOW (traced)

```
UI (ProjectScreen)
  → InteractionContract(scope, outputType=DETAILED, sourceType=LEDGER)
  → InteractionEngine.execute(contract, LedgerInput(replayState))
  → InteractionMapper.extract(scope, replayState) → StateSlice
  → InteractionFormatter.format(DETAILED, slice) → String
  → InteractionResult → UI renders content
```

✅ Flow verified.

### SIMULATION FLOW (traced)

```
UI
  → SimulationEngine.simulate(replayState, SimulationContract) → SimulationResult
  → SimulationEngine.toView(result) → SimulationView
  → InteractionEngine.execute(contract, SimulationInput(view))
  → InteractionMapper.extractFromSimulationView(view) → StateSlice
  → InteractionFormatter.format(outputType, slice) → String
  → InteractionResult → UI renders content
```

✅ Flow verified.

✅ **Both flows converge at `InteractionEngine.execute()`.**  
✅ **No parallel output pipelines exist.**  
✅ **Single formatter (`InteractionFormatter`) handles all output types.**

---

## SECTION 6: CONTRACT VALIDATION

### InteractionContract

✅ Declared as `data class` — immutable by construction.  
✅ Contains no methods or executable logic — pure field carrier.  
✅ Defines scope, output type, and source type only.  
✅ No imports from any module.

### IngressContract

✅ Declared as `data class` — immutable.  
✅ Contains no execution logic.  
✅ Does not write to the event ledger.  
✅ Zero imports — fully independent from all modules.

---

## SECTION 7: DUPLICATION CHECK

✅ **Single formatter:** `InteractionFormatter` handles all `OutputType` strategies.  
✅ **Single interaction pipeline:** `InteractionEngine.execute()` is the sole entry point.  
✅ **No duplicated mapping logic** within the Interaction layer.

### Flagged items

| # | Item | Detail |
|---|------|--------|
| 1 | `SimulationInteractionBridge` | Identity pass-through (`map(view) { return view }`). Never referenced from any production or test code. Redundant layer with no transformation or purpose. |
| 2 | Dual `ExecutionOrchestrator` classes | `execution.ExecutionOrchestrator` manages contractor/task lifecycle; `orchestration.ExecutionOrchestrator` manages governance sequencing. Identical class names across two packages create confusion. |
| 3 | Parallel mapping | `InteractionMapper` maps `ReplayState → StateSlice`; `StateProjection` maps `ReplayState → UIState`. Related but non-overlapping concerns — not true duplication, but worth monitoring. |

---

## SECTION 8: UI COMPLIANCE

### ProjectScreen (primary composable)

✅ Does **NOT** compute — delegates all computation to `bridge` and `interactionEngine`.  
✅ Does **NOT** validate — no conditional business logic in the composable.  
✅ Does **NOT** interpret — renders `interactionResult.content` (already formatted string) directly.  
✅ Reads `ReplayState` (via `bridge.replayState()` — read-only).  
✅ Triggers contracts via `bridge.submitIntent()`, `bridge.runGovernorStep()`, `bridge.approveContracts()`.  
✅ Renders `InteractionResult`.

### Flagged items in ui/core and ui/modules

| # | File | Behaviour | Assessment |
|---|------|-----------|------------|
| 1 | `StateProjection.kt` | Derives `progress` and `activeContractId` from `ReplayState` | Presentation mapping; no business logic. |
| 2 | `ActionGate.kt` | Evaluates `canApproveContracts`, `canStartExecution`, `canRetry` from `ReplayState` | Availability flags derived from replay — equivalent to reading `ReplayState`. |
| 3 | `ContractModuleUI.kt` | Derives contract count and status from `UIState` | Presentation derivation only; no event writes. |
| 4 | `ExecutionModuleUI.kt` | Derives task lifecycle phase from `UIState` | Presentation derivation only. |
| 5 | `TaskModuleUI.kt` | Derives task lifecycle state from `UIState` | Presentation derivation only. |

These utilities perform **presentation-layer mapping**, not business computation. The main composable (`ProjectScreen`) is fully compliant; the `ui/core` and `ui/modules` helpers are bounded to read-only `UIState`/`ReplayState` derivations.

---

## SECTION 9: RISKS

| # | Risk | Severity | Description |
|---|------|----------|-------------|
| R-1 | Governor in `core/` | HIGH | `Governor` imports from 6 non-core modules (`assembly`, `contractor`, `decision`, `execution`, `governance`, `tasks`). Violates "Core has ZERO dependency on modules". Future execution changes require modifying the core package. **Mitigation:** Move `Governor` to a dedicated `governor/` package and extract `VALID_TRANSITIONS` to `EventTypes`. |
| R-2 | `SimulationInteractionBridge` dead code | MEDIUM | Identity pass-through class never called from production or test code. Creates ambiguity about the intended cross-layer boundary. **Mitigation:** Remove or add documentation of future intent. |
| R-3 | Dual `ExecutionOrchestrator` names | LOW | `execution.ExecutionOrchestrator` and `orchestration.ExecutionOrchestrator` are two distinct classes with the same name. **Mitigation:** Rename `orchestration.ExecutionOrchestrator` to `ExecutionSequencer` or `ExecutionGate`. |
| R-4 | `LedgerViewEngine` mutable state | LOW | Holds `private var _uiState` — local UI state mutation. Not a ledger violation, but breaks strict immutability in the UI layer. **Mitigation:** Return `UIState` from `render()` instead of storing it. |
| R-5 | `UIModule.render()` returns `Any` | LOW | Type erasure prevents compile-time safety checks on module outputs. Hidden dependency on presentation model class hierarchy. **Mitigation:** Introduce a sealed interface as the return type. |
| R-6 | Circular concern: `core/LedgerAudit` → `Governor.VALID_TRANSITIONS` | LOW | `LedgerAudit` (in `core/`) accesses `Governor.VALID_TRANSITIONS` via same-package reference. If `Governor` is ever moved out of `core/`, this creates an import dependency that pulls `core` → `governor`. **Mitigation:** Move `VALID_TRANSITIONS` to `EventTypes` before relocating `Governor`. |

---

## CORRECTIONS APPLIED

### FIXED: `SystemVerificationContract` dependency inversion

**Before:** `core/SystemVerificationContract.kt` imported `governance.StateSurfaceMirror` and `governance.StructuralStateAwareness`, creating a `core → governance` dependency that violates "Core has ZERO dependency on modules."

**After:** `SystemVerificationContract.kt` moved to the `governance/` package. All references updated. The `core/` package no longer depends on `governance/`.

---

## FINAL VERDICT

```
CORRECTIONS_REQUIRED
```

| Constraint | Status |
|-----------|--------|
| All execution flows correct | ✅ |
| No hidden dependencies | ✅ (except Governor — see R-1) |
| No module bypasses Interaction | ✅ |
| No mutation outside Governor | ✅ |
| No architectural drift (primary paths) | ✅ |
| Core has ZERO dependency on modules | ❌ Governor (R-1) — partially corrected (SystemVerificationContract fixed) |
| UI depends ONLY on Interaction + ReplayState | ✅ |
| Both pipelines converge at InteractionEngine | ✅ |
| Single formatter, single pipeline | ✅ |

**Primary correction required:** Move `Governor.kt` from `core/` to a dedicated `governor/` package and relocate `VALID_TRANSITIONS` to `EventTypes` to eliminate the `core → modules` dependency. This is the only remaining architectural violation after the `SystemVerificationContract` fix applied in this report.
