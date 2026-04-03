# AGOII–ARTIFACT-SPINE-CONTINUITY-005 — Design Document

**Execution → Artifact → Ledger Continuity Lock**

---

## Executive Summary

This document outlines the design for implementing artifact persistence into a ledger/event system, closing the final structural gap in the Agoii architecture. This enables cross-execution validation, delta enforcement, and true convergence capability.

**Status:** Design Phase  
**Complexity:** High (New architectural components)  
**Impact:** Foundational (Enables future validation features)

---

## Problem Statement

### Current State

```
Execution → Artifact → Validation ✅
```

**Achieved:**
- Artifacts are created by contractors
- Artifacts are validated structurally
- Artifacts have cryptographic integrity (SHA-256)

### Missing State

```
Artifact → Ledger → Replay → Next Validation ❌
```

**NOT Achieved:**
- Artifacts are NOT persisted
- No historical artifact comparison
- No delta tracking across executions
- No convergence memory
- Replay is conceptual, not implemented

### Consequence

Each execution exists in isolation:
- ✅ Self-contained truth (single execution)
- ❌ Cumulative truth (across executions)

**Result:** Cannot enforce regression rules, delta control, or convergence

---

## Solution Overview

### Three New Components

1. **Event Schema** (`agoii/schema/ledger_schema.ts`)
   - Define TASK_EXECUTED event structure
   - Define artifact hash format (NO full content)
   - Define event types

2. **Event Ledger** (`agoii/ledger/event_ledger.ts`)
   - Append-only event storage
   - File-based persistence
   - Event emission API

3. **Replay** (`agoii/ledger/replay.ts`)
   - Historical artifact query
   - Delta reconstruction
   - Validation support

### Integration Point

**Modified:** `execute.js`
- After artifact validation: emit TASK_EXECUTED event
- Event contains artifact HASHES only (not content)

---

## Detailed Design

### 1. Event Schema

**File:** `agoii/schema/ledger_schema.ts`

```typescript
/** Event types in the ledger */
export type LedgerEventType = "TASK_EXECUTED";

/** Section hash for ledger storage (NO content) */
export interface ArtifactSectionHash {
  section_id: string;
  content_hash: string;
}

/** Artifact hash structure for ledger */
export interface ArtifactHash {
  sections: ArtifactSectionHash[];
}

/** TASK_EXECUTED event structure */
export interface TaskExecutedEvent {
  event_type: "TASK_EXECUTED";
  execution_id: string;
  status: "success" | "failure" | "timeout" | "rejected";
  artifact: ArtifactHash;
  timestamp: string;
  metadata?: {
    contractor_id?: string;
    durationMs?: number;
  };
}

/** Union of all event types */
export type LedgerEvent = TaskExecutedEvent;
```

**Critical Rules:**
- ✅ Store ONLY `content_hash`, NEVER full `content`
- ✅ All events have `event_type` discriminator
- ✅ All events have `timestamp` (ISO-8601)

**Rationale:**
- **Security:** Content may contain sensitive data
- **Efficiency:** Hashes are fixed size (64 chars)
- **Determinism:** Hashes enable exact comparison

---

### 2. Event Ledger

**File:** `agoii/ledger/event_ledger.ts`

```typescript
import * as fs from 'fs';
import * as path from 'path';
import { LedgerEvent } from '../schema/ledger_schema';

/**
 * Append-only event ledger for artifact persistence.
 * 
 * Design principles:
 * - Append-only (immutability)
 * - File-based (simple, no DB)
 * - Line-delimited JSON (NDJSON)
 * - Thread-safe writes
 */
export class EventLedger {
  private ledgerPath: string;

  constructor(ledgerPath: string) {
    this.ledgerPath = ledgerPath;
    this.ensureLedgerExists();
  }

  /**
   * Append an event to the ledger.
   * Thread-safe via fs.appendFileSync.
   */
  append(event: LedgerEvent): void {
    const line = JSON.stringify(event) + '\n';
    fs.appendFileSync(this.ledgerPath, line, 'utf8');
  }

  /**
   * Read all events from the ledger.
   * Returns events in chronological order.
   */
  readAll(): LedgerEvent[] {
    if (!fs.existsSync(this.ledgerPath)) {
      return [];
    }

    const content = fs.readFileSync(this.ledgerPath, 'utf8');
    const lines = content.split('\n').filter(line => line.trim());
    
    return lines.map(line => {
      try {
        return JSON.parse(line) as LedgerEvent;
      } catch (err) {
        throw new Error(`Ledger corruption: invalid JSON at line: ${line}`);
      }
    });
  }

  /**
   * Read events for a specific execution_id.
   */
  readByExecutionId(execution_id: string): LedgerEvent[] {
    return this.readAll().filter(
      event => event.execution_id === execution_id
    );
  }

  private ensureLedgerExists(): void {
    const dir = path.dirname(this.ledgerPath);
    if (!fs.existsSync(dir)) {
      fs.mkdirSync(dir, { recursive: true });
    }
    if (!fs.existsSync(this.ledgerPath)) {
      fs.writeFileSync(this.ledgerPath, '', 'utf8');
    }
  }
}
```

**Storage Format:** NDJSON (Newline-Delimited JSON)
```
{"event_type":"TASK_EXECUTED","execution_id":"exec-001",...}
{"event_type":"TASK_EXECUTED","execution_id":"exec-002",...}
```

**Advantages:**
- Simple to implement
- Human-readable
- Append-only (immutable)
- No database dependency
- Easy to backup/restore

**Limitations:**
- Linear scan for queries (acceptable for current scale)
- No built-in indexing (can add later if needed)

---

### 3. Replay Component

**File:** `agoii/ledger/replay.ts`

```typescript
import { EventLedger } from './event_ledger';
import { TaskExecutedEvent, ArtifactHash } from '../schema/ledger_schema';

/**
 * Replay provides historical artifact queries for validation.
 * 
 * Enables:
 * - Delta detection (compare current vs previous)
 * - Regression detection (did output change when it shouldn't?)
 * - Convergence tracking (are we converging to a solution?)
 */
export class Replay {
  private ledger: EventLedger;

  constructor(ledger: EventLedger) {
    this.ledger = ledger;
  }

  /**
   * Get the last successful execution artifact for a given execution_id prefix.
   * 
   * Use case: Find the previous successful execution to compare against.
   * 
   * @param executionIdPrefix - Filter executions by ID prefix (e.g., "task-123")
   * @returns Last successful artifact hash, or null if none found
   */
  getLastSuccessfulArtifact(executionIdPrefix: string): ArtifactHash | null {
    const events = this.ledger.readAll();
    
    // Filter: TASK_EXECUTED + success + matching prefix
    const successfulEvents = events
      .filter((e): e is TaskExecutedEvent => 
        e.event_type === 'TASK_EXECUTED' &&
        e.status === 'success' &&
        e.execution_id.startsWith(executionIdPrefix)
      );

    // Get last one
    if (successfulEvents.length === 0) {
      return null;
    }

    const lastEvent = successfulEvents[successfulEvents.length - 1];
    return lastEvent.artifact;
  }

  /**
   * Get all successful artifacts for an execution ID prefix.
   * Returns in chronological order.
   */
  getArtifactHistory(executionIdPrefix: string): ArtifactHash[] {
    const events = this.ledger.readAll();
    
    return events
      .filter((e): e is TaskExecutedEvent => 
        e.event_type === 'TASK_EXECUTED' &&
        e.status === 'success' &&
        e.execution_id.startsWith(executionIdPrefix)
      )
      .map(e => e.artifact);
  }

  /**
   * Compare two artifact hashes for equality.
   * 
   * Returns true if:
   * - Same number of sections
   * - Each section has same section_id and content_hash
   */
  artifactsEqual(a: ArtifactHash, b: ArtifactHash): boolean {
    if (a.sections.length !== b.sections.length) {
      return false;
    }

    for (let i = 0; i < a.sections.length; i++) {
      const sectionA = a.sections[i];
      const sectionB = a.sections.find(s => s.section_id === sectionA.section_id);

      if (!sectionB) {
        return false; // Section ID missing in b
      }

      if (sectionA.content_hash !== sectionB.content_hash) {
        return false; // Content changed
      }
    }

    return true;
  }

  /**
   * Detect if regression occurred.
   * 
   * Regression = new artifact identical to previous when change was expected.
   * 
   * @returns true if regression detected
   */
  detectRegression(
    previous: ArtifactHash | null,
    current: ArtifactHash,
    changeExpected: boolean
  ): boolean {
    if (previous === null) {
      return false; // No previous to compare against
    }

    const identical = this.artifactsEqual(previous, current);

    // Regression if: identical when change was expected
    return identical && changeExpected;
  }
}
```

**Usage Example:**

```typescript
const ledger = new EventLedger('/tmp/nemoclaw-ledger.ndjson');
const replay = new Replay(ledger);

// Get last successful artifact
const previous = replay.getLastSuccessfulArtifact('task-123');

// Compare with current
const current: ArtifactHash = { sections: [...] };
const changed = !replay.artifactsEqual(previous, current);

// Detect regression
const regression = replay.detectRegression(previous, current, true);
```

---

### 4. Integration with execute.js

**File:** `execute.js` (modified)

**Current flow:**
```javascript
1. Validate contract
2. Execute contractor
3. Validate artifact
4. Build report
5. Write report to stdout
```

**New flow:**
```javascript
1. Validate contract
2. Execute contractor
3. Validate artifact
4. Build report
5. *** EMIT TASK_EXECUTED EVENT TO LEDGER ***
6. Write report to stdout
```

**Implementation:**

```javascript
// At top of execute.js
const { EventLedger } = require('./agoii/dist/ledger/event_ledger');

// Initialize ledger (singleton)
const LEDGER_PATH = process.env.NEMOCLAW_LEDGER_PATH || '/tmp/nemoclaw-ledger.ndjson';
const eventLedger = new EventLedger(LEDGER_PATH);

// After building report, before stdout
function emitTaskExecutedEvent(report) {
  // Extract only hashes (NOT content)
  const artifactHash = {
    sections: report.artifact.sections.map(section => ({
      section_id: section.section_id,
      content_hash: section.content_hash
      // CRITICAL: Do NOT include 'content'
    }))
  };

  const event = {
    event_type: 'TASK_EXECUTED',
    execution_id: report.execution_id,
    status: report.status,
    artifact: artifactHash,
    timestamp: report.metadata.timestamp,
    metadata: {
      contractor_id: report.metadata.contractor_id,
      durationMs: report.metadata.durationMs
    }
  };

  eventLedger.append(event);
}

// In main orchestrator, after buildExecutionReport
const report = buildExecutionReport(...);
emitTaskExecutedEvent(report);  // NEW
console.log(JSON.stringify(report));
```

**Environment Variable:**
- `NEMOCLAW_LEDGER_PATH`: Override default ledger location
- Default: `/tmp/nemoclaw-ledger.ndjson`

---

## File Structure

### New Files

```
agoii/
  schema/
    ledger_schema.ts          # Event type definitions
  ledger/
    event_ledger.ts           # Append-only event storage
    replay.ts                 # Historical query API

test/
  ledger.test.js              # Ledger tests
  replay.test.js              # Replay tests
  
docs/
  AGOII-ARTIFACT-SPINE-CONTINUITY-005-IMPL.md    # Implementation report
  ARTIFACT-CONTINUITY-005-QUICKREF.md            # Quick reference
```

### Modified Files

```
execute.js                    # Add event emission
```

---

## Testing Strategy

### 1. Ledger Tests (`test/ledger.test.js`)

```javascript
describe('EventLedger', () => {
  it('appends events in order', () => {
    const ledger = new EventLedger('/tmp/test-ledger.ndjson');
    ledger.append({ event_type: 'TASK_EXECUTED', ... });
    const events = ledger.readAll();
    assert(events.length === 1);
  });

  it('reads events by execution_id', () => {
    // Test filtering
  });

  it('handles empty ledger', () => {
    // Test empty case
  });

  it('detects ledger corruption', () => {
    // Test invalid JSON handling
  });
});
```

### 2. Replay Tests (`test/replay.test.js`)

```javascript
describe('Replay', () => {
  it('gets last successful artifact', () => {
    // Test history query
  });

  it('returns null when no history', () => {
    // Test empty case
  });

  it('compares artifacts correctly', () => {
    // Test equality
  });

  it('detects regression', () => {
    // Test regression detection
  });

  it('ignores failed executions', () => {
    // Test filtering
  });
});
```

### 3. Integration Tests

```javascript
describe('execute.js integration', () => {
  it('emits TASK_EXECUTED on success', () => {
    // Execute contract, verify event in ledger
  });

  it('emits TASK_EXECUTED on failure', () => {
    // Execute failing contract, verify event
  });

  it('stores only hashes, not content', () => {
    // Verify content is NOT in ledger
  });
});
```

---

## Security Considerations

### 1. Content Isolation

**Rule:** NEVER store full artifact content in ledger

**Rationale:**
- Content may contain sensitive data (API keys, credentials, PII)
- Ledger is persistent storage (harder to secure)
- Hashes are sufficient for validation

**Enforcement:**
```typescript
// In emitTaskExecutedEvent()
const artifactHash = {
  sections: report.artifact.sections.map(section => ({
    section_id: section.section_id,
    content_hash: section.content_hash
    // *** NO content field ***
  }))
};
```

### 2. Ledger Access Control

**Considerations:**
- Ledger file should have restricted permissions (600)
- Consider encryption at rest for production
- Log rotation strategy for long-running systems

**Future:**
- Add ledger encryption option
- Add access audit logging

---

## Migration Strategy

### Phase 1: Implementation (Current)

- Implement schema, ledger, replay
- Add event emission to execute.js
- Write comprehensive tests

**Backward Compatibility:** ✅ YES
- execute.js continues to output same report format
- Ledger emission is additive (no breaking changes)
- Can be disabled via environment variable

### Phase 2: Validation (Future)

- Implement cross-execution validation
- Add delta enforcement rules
- Add regression detection

**Depends on:** Phase 1 complete

### Phase 3: ExecutionAuthority (Future)

- Create ExecutionAuthority component
- Integrate with Replay for validation
- Enforce convergence rules

**Depends on:** Phase 2 complete

---

## Performance Considerations

### Current Design

**Storage:**
- NDJSON file (simple, human-readable)
- Linear scan for queries
- No indexing

**Scale:**
- ✅ Good for: < 100k events
- ⚠️ Slow for: > 100k events
- ❌ Not suitable for: millions of events

### Future Optimizations (if needed)

1. **Indexing:** Add in-memory index on execution_id
2. **Partitioning:** Split ledger by date/task
3. **Compression:** Compress old ledger segments
4. **Database:** Migrate to SQLite for large scale

**Decision:** Start simple, optimize later based on actual usage

---

## Open Questions

### 1. Ledger Location

**Options:**
- A: `/tmp/nemoclaw-ledger.ndjson` (default, ephemeral)
- B: `~/.nemoclaw/ledger.ndjson` (persistent)
- C: Configurable via env var (flexible)

**Recommendation:** C (configurable, default to `/tmp`)

### 2. Ledger Rotation

**Question:** When to rotate/archive old events?

**Options:**
- Manual rotation (user responsibility)
- Auto-rotation by size (e.g., 100MB)
- Auto-rotation by time (e.g., daily)

**Recommendation:** Start with manual, add auto-rotation if needed

### 3. Event Schema Versioning

**Question:** How to handle schema evolution?

**Options:**
- A: Add `schema_version` field to events
- B: Use event_type versioning (e.g., TASK_EXECUTED_V2)
- C: No versioning (breaking changes only)

**Recommendation:** A (schema_version field for future-proofing)

---

## Success Criteria

**Phase 1 Complete when:**
- ✅ Event schema defined and documented
- ✅ EventLedger implemented and tested
- ✅ Replay implemented and tested
- ✅ execute.js emits events
- ✅ All tests passing
- ✅ Documentation complete

**System Ready when:**
- ✅ Artifacts persist across executions
- ✅ Historical artifacts queryable
- ✅ Delta comparison possible
- ✅ No content leakage (hashes only)

---

## Timeline Estimate

**Complexity:** High (new architectural components)

**Estimated Effort:**
- Event Schema: 1 hour
- Event Ledger: 3 hours
- Replay: 3 hours
- Integration: 2 hours
- Tests: 4 hours
- Documentation: 2 hours

**Total:** ~15 hours of focused development

**Risks:**
- Schema evolution complexity
- File I/O error handling
- Thread safety edge cases

---

## Conclusion

AGOII-ARTIFACT-SPINE-CONTINUITY-005 adds the missing persistence layer to enable cross-execution validation. This is a foundational change that:

1. **Enables** delta tracking, regression detection, convergence
2. **Maintains** backward compatibility (additive change)
3. **Preserves** security (hashes only, no content)
4. **Keeps** simple (file-based, no DB dependency)

**Recommendation:** Proceed with implementation in phases, starting with event schema and ledger core.

---

**Document Version:** 1.0  
**Date:** 2026-04-03  
**Status:** DESIGN COMPLETE  
**Next Step:** Implementation Phase 1 (Event Schema)
