# Agoii Mobile Core (APK)

A minimal Android application that embeds and runs the **Agoii deterministic event-driven execution engine** locally on device.

---

## Architecture

```
App
└── ProjectScreen
    ├── Header          — project_id + audit status (VALID / INVALID)
    ├── StatePanel      — derived from replay only (never direct state)
    ├── EventList       — raw ledger events, scrollable, no grouping
    ├── ActionBar       — RUN STEP + conditional APPROVE
    └── InputBar        — objective text field + SEND
```

### Layers

| Layer | Package | Responsibility |
|---|---|---|
| **Core** | `com.agoii.mobile.core` | event_store, governor, ledger_audit, replay, replay_test |
| **Bridge** | `com.agoii.mobile.bridge` | Thin adapter — calls core, never adds logic |
| **UI** | `com.agoii.mobile.ui` | Compose screen — read-only display + user input |

---

## System Laws (Non-Negotiable)

1. The system is **event-driven** — all state derives from the ledger.
2. **No direct state mutation** — `appendEvent` is the only write path.
3. **Governor is the only execution authority** — UI never computes business logic.
4. **Ledger is append-only** — events are never modified or deleted.
5. **One action = one event** — no batching, no hidden transitions.

---

## Execution Flow

```
User submits intent
  → append_event("intent_submitted", {objective})

User taps RUN STEP (repeat until waiting):
  → run_governor(project_id)
  → Appends: contracts_generated → contracts_ready

System STOPS — UI shows APPROVE button
  (phase == contracts_ready)

User taps APPROVE:
  → append_event("contracts_approved")

User taps RUN STEP (repeat until complete):
  → run_governor(project_id)
  → Appends: execution_started
  → Appends: contract_executed × 3
  → Appends: assembly_completed

After every action, UI reloads:
  - events    = load_events(project_id)
  - state     = replay(project_id)
  - audit     = audit_ledger(project_id)
  - verify    = verify_replay(project_id)
```

---

## Core Module Reference

### EventRepository (interface)
```kotlin
fun appendEvent(projectId: String, type: String, payload: Map<String, Any>)
fun loadEvents(projectId: String): List<Event>
```

### EventStore
- Implements `EventRepository`
- Stores one JSON file per project in `context.filesDir/ledgers/<projectId>.json`
- Writes are atomic (temp-file swap)
- Append-only; no delete/update paths

### Governor
```kotlin
fun runGovernor(projectId: String): GovernorResult
// → ADVANCED | WAITING_FOR_APPROVAL | COMPLETED | NO_EVENT
```

Valid automatic transitions:
```
intent_submitted    → contracts_generated
contracts_generated → contracts_ready
contracts_approved  → execution_started
execution_started   → contract_executed (× total_contracts)
contract_executed   → assembly_completed (when all executed)
```

User-driven events (not governor):
```
contracts_ready → contracts_approved  (user taps APPROVE)
intent_submitted                      (user taps SEND)
```

### LedgerAudit
```kotlin
fun auditLedger(projectId: String): AuditResult
// AuditResult(valid, errors, checkedEvents)
```

### Replay
```kotlin
fun replay(projectId: String): ReplayState
// ReplayState(phase, contractsCompleted, totalContracts,
//             executionStarted, executionCompleted, objective)
```

### ReplayTest
```kotlin
fun verifyReplay(projectId: String): ReplayVerification
// ReplayVerification(valid, replayState, auditResult, invariantErrors)
```

---

## Event Structure

```json
{
  "type": "string",
  "payload": {}
}
```

Allowed event types: `intent_submitted`, `contracts_generated`, `contracts_ready`,
`contracts_approved`, `execution_started`, `contract_executed`, `assembly_completed`

---

## Build Instructions

### Prerequisites

| Tool | Version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or later |
| Android SDK | API 34 (compileSdk) |
| JDK | 17 |
| Gradle | 8.7 (via wrapper) |

### Steps

```bash
# 1. Clone the repository
git clone https://github.com/Rogmar0071/Agoii-UI.git
cd Agoii-UI

# 2. Build from command line:

# Debug APK
./gradlew assembleDebug

# Release APK (unsigned)
./gradlew assembleRelease

# Run unit tests (JVM only, no device required)
./gradlew test

# Output APK location
ls app/build/outputs/apk/debug/app-debug.apk
```

### Installing on Device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Project Structure

```
AgoiiMobile/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/agoii/mobile/
│   │   │   │   ├── core/
│   │   │   │   │   ├── Event.kt              # Event data class
│   │   │   │   │   ├── EventRepository.kt    # Interface (for testability)
│   │   │   │   │   ├── EventStore.kt         # Append-only file-backed store
│   │   │   │   │   ├── Governor.kt           # Sole execution authority
│   │   │   │   │   ├── LedgerAudit.kt        # Transition validator
│   │   │   │   │   ├── Replay.kt             # State derivation from events
│   │   │   │   │   └── ReplayTest.kt         # Invariant checker
│   │   │   │   ├── bridge/
│   │   │   │   │   └── CoreBridge.kt         # UI ↔ core adapter
│   │   │   │   └── ui/
│   │   │   │       ├── MainActivity.kt       # Activity entry point
│   │   │   │       ├── ProjectScreen.kt      # Main Compose screen
│   │   │   │       └── theme/
│   │   │   │           └── Theme.kt          # Dark theme + colours
│   │   │   ├── res/values/
│   │   │   │   ├── strings.xml
│   │   │   │   └── themes.xml
│   │   │   └── AndroidManifest.xml
│   │   └── test/java/com/agoii/mobile/
│   │       └── CoreTest.kt                   # 16 JVM unit tests
│   ├── build.gradle
│   └── proguard-rules.pro
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradle/wrapper/
│   ├── gradle-wrapper.jar
│   └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
└── README.md
```

---

## Validation

After a full run the system is correct if and only if:

| Check | Expected |
|---|---|
| `audit_ledger(project_id).valid` | `true` |
| `verify_replay(project_id).valid` | `true` |
| `verify_replay(...).invariantErrors` | `[]` |
| `replay(project_id).phase` | `"assembly_completed"` |
| `replay(project_id).contractsCompleted` | `3` |
| `replay(project_id).executionCompleted` | `true` |

---

## Visual Style

| Element | Colour |
|---|---|
| Background | `#0D0D0D` (near black) |
| Surface | `#1A1A1A` |
| System events | `#424242` (grey) |
| Execution events | `#1565C0` (blue) |
| Approval events | `#E65100` (orange) |
| Completion events | `#2E7D32` (green) |
| Accent | `#4FC3F7` (light blue) |

---

## Definition of Success

The system is correct **only** if:

- Ledger passes audit ✓
- Replay matches execution ✓
- No invariant violations occur ✓
- Execution is fully step-driven ✓
- No hidden logic exists ✓

> This is **not** a chat application. It is a deterministic execution engine presented through a mobile interface.
