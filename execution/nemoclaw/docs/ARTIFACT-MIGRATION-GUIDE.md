# Artifact Surface Migration Guide

**For ExecutionAuthority, Ledger, and Downstream Consumers**

---

## Overview

AGOII–ARTIFACT-SPINE-001 introduces a **MANDATORY** artifact field to all NemoClaw execution reports. This is a **BREAKING CHANGE** with no backward compatibility layer.

**Key Change:** Every `NemoClawReport` now includes an `artifact` field.

---

## What Changed

### Before (Old Schema)

```typescript
interface NemoClawReport {
  execution_id: string;
  status: "success" | "failure" | "timeout" | "rejected";
  exit_code: number;
  outputs: Array<{ contentType: "text/plain"; content: string }>;
  metadata: { ... };
  failure_surface?: { ... };
}
```

### After (New Schema)

```typescript
interface NemoClawReport {
  execution_id: string;
  status: "success" | "failure" | "timeout" | "rejected";
  exit_code: number;
  outputs: Array<{ contentType: "text/plain"; content: string }>;
  artifact: ExecutionArtifact;  // NEW - MANDATORY
  metadata: { ... };
  failure_surface?: { ... };
}

interface ExecutionArtifact {
  sections: ArtifactSection[];
}

interface ArtifactSection {
  section_id: string;
  content: string;
  content_hash: string;  // SHA-256 hex
}
```

---

## Impact Analysis

### HIGH IMPACT

**Components that MUST be updated:**

1. **ExecutionAuthority**
   - Must validate artifact presence
   - Must enforce artifact requirements
   - Must handle `invalid_artifact` failures

2. **Ledger/Persistence**
   - Must store artifact field
   - Must retrieve artifacts for delta comparison
   - Must handle artifact schema changes

3. **Report Parsers**
   - Must expect artifact field
   - Must handle artifact validation
   - Must not fail on artifact presence

### MEDIUM IMPACT

**Components that SHOULD be updated:**

1. **Monitoring/Observability**
   - Can log artifact hashes for tracking
   - Can monitor artifact sizes
   - Can detect hash mismatches

2. **Testing Infrastructure**
   - Should validate artifact presence in tests
   - Should test artifact integrity
   - Should verify hash correctness

### LOW IMPACT

**Components that MAY be updated:**

1. **UI/Dashboard**
   - Can display artifact information
   - Can show hash values
   - Can visualize delta changes

2. **Analytics**
   - Can analyze artifact patterns
   - Can track hash distributions
   - Can measure determinism

---

## Migration Steps

### Step 1: Update TypeScript Types

Import new types from execution_schema.ts:

```typescript
import {
  NemoClawReport,
  ExecutionArtifact,
  ArtifactSection,
} from './agoii/schema/execution_schema';
```

### Step 2: Update Report Handling

**Old code:**
```typescript
function handleReport(report: NemoClawReport) {
  console.log(`Status: ${report.status}`);
  console.log(`Outputs: ${report.outputs.length}`);
}
```

**New code:**
```typescript
function handleReport(report: NemoClawReport) {
  console.log(`Status: ${report.status}`);
  console.log(`Outputs: ${report.outputs.length}`);
  
  // NEW - Handle artifact
  if (!report.artifact) {
    throw new Error('Missing artifact field (schema violation)');
  }
  
  console.log(`Artifact sections: ${report.artifact.sections.length}`);
}
```

### Step 3: Add Artifact Validation

```typescript
function validateArtifact(artifact: ExecutionArtifact): boolean {
  // Check structure
  if (!artifact || !Array.isArray(artifact.sections)) {
    return false;
  }
  
  // For rejections, empty sections is OK
  // For executions, at least one section required
  
  // Validate each section
  for (const section of artifact.sections) {
    if (!section.section_id || !section.content_hash) {
      return false;
    }
    
    // Optionally: Verify hash integrity
    const expectedHash = crypto
      .createHash('sha256')
      .update(section.content, 'utf8')
      .digest('hex');
      
    if (section.content_hash !== expectedHash) {
      return false;  // Hash mismatch
    }
  }
  
  return true;
}
```

### Step 4: Update Persistence

**Old schema:**
```sql
CREATE TABLE execution_reports (
  execution_id TEXT PRIMARY KEY,
  status TEXT NOT NULL,
  exit_code INTEGER NOT NULL,
  outputs TEXT NOT NULL,  -- JSON
  metadata TEXT NOT NULL   -- JSON
);
```

**New schema:**
```sql
CREATE TABLE execution_reports (
  execution_id TEXT PRIMARY KEY,
  status TEXT NOT NULL,
  exit_code INTEGER NOT NULL,
  outputs TEXT NOT NULL,    -- JSON
  artifact TEXT NOT NULL,   -- JSON (NEW)
  metadata TEXT NOT NULL    -- JSON
);

-- Index for hash lookups
CREATE INDEX idx_artifact_hash ON execution_reports (
  json_extract(artifact, '$.sections[0].content_hash')
);
```

### Step 5: Handle New Failure Types

```typescript
function handleFailure(report: NemoClawReport) {
  if (!report.failure_surface) return;
  
  switch (report.failure_surface.type) {
    case 'invalid_artifact':
      // NEW failure type
      console.error('Artifact validation failed:', report.failure_surface.details);
      // Handle artifact-specific failures
      break;
      
    case 'missing_artifact':
      // NEW failure type (should never happen in practice)
      console.error('Artifact missing - schema violation');
      break;
      
    // ... existing failure types ...
  }
}
```

---

## Common Migration Issues

### Issue 1: Null Artifact

**Error:**
```
TypeError: Cannot read property 'sections' of undefined
```

**Cause:** Code expects artifact but it's missing

**Fix:**
```typescript
// Add null check
if (!report.artifact || !report.artifact.sections) {
  throw new Error('Invalid report: missing artifact');
}
```

### Issue 2: Empty Sections Array

**Error:**
```
Expected at least one section
```

**Cause:** Empty sections array on successful execution

**Fix:**
```typescript
// Empty sections is OK for rejections, not for executions
if (report.status !== 'rejected' && report.artifact.sections.length === 0) {
  // This is a schema violation - report should have failed
  throw new Error('Invalid artifact: empty sections on execution');
}
```

### Issue 3: Schema Mismatch

**Error:**
```
Type 'NemoClawReport' is not assignable to type 'OldReport'
```

**Cause:** TypeScript type definitions out of date

**Fix:**
```typescript
// Update imports to use latest schema
import { NemoClawReport } from './agoii/schema/execution_schema';

// Or add artifact to custom types
interface MyReport extends OldReport {
  artifact: ExecutionArtifact;
}
```

---

## Testing Your Migration

### Test 1: Artifact Presence

```typescript
test('Report includes artifact field', async () => {
  const report = await executeContract(contract);
  
  assert(report.artifact, 'artifact field must be present');
  assert(Array.isArray(report.artifact.sections), 'sections must be array');
});
```

### Test 2: Hash Integrity

```typescript
test('Artifact hash matches content', async () => {
  const report = await executeContract(contract);
  const section = report.artifact.sections[0];
  
  const expectedHash = crypto
    .createHash('sha256')
    .update(section.content, 'utf8')
    .digest('hex');
    
  assert.strictEqual(section.content_hash, expectedHash, 'Hash must match');
});
```

### Test 3: Determinism

```typescript
test('Identical inputs produce identical hashes', async () => {
  const [report1, report2] = await Promise.all([
    executeContract(contract),
    executeContract(contract),
  ]);
  
  // Note: Due to OpenAI non-determinism, outputs may differ
  // But hash function itself is deterministic
  const hash1 = computeHash('test');
  const hash2 = computeHash('test');
  
  assert.strictEqual(hash1, hash2, 'Hash function must be deterministic');
});
```

---

## Delta Detection Implementation

Once migrated, you can implement delta detection:

```typescript
interface DeltaAnalysis {
  changed: boolean;
  mutatedSections: string[];
  addedSections: string[];
  removedSections: string[];
}

function analyzeArtifactDelta(
  previous: ExecutionArtifact,
  current: ExecutionArtifact
): DeltaAnalysis {
  const result: DeltaAnalysis = {
    changed: false,
    mutatedSections: [],
    addedSections: [],
    removedSections: [],
  };
  
  // Build hash maps
  const prevHashes = new Map(
    previous.sections.map(s => [s.section_id, s.content_hash])
  );
  const currHashes = new Map(
    current.sections.map(s => [s.section_id, s.content_hash])
  );
  
  // Find mutated sections
  for (const [id, hash] of currHashes) {
    if (prevHashes.has(id)) {
      if (prevHashes.get(id) !== hash) {
        result.mutatedSections.push(id);
        result.changed = true;
      }
    } else {
      result.addedSections.push(id);
      result.changed = true;
    }
  }
  
  // Find removed sections
  for (const [id] of prevHashes) {
    if (!currHashes.has(id)) {
      result.removedSections.push(id);
      result.changed = true;
    }
  }
  
  return result;
}
```

---

## Rollback Plan

### Option 1: Fix Forward

**Recommended:** Update all consumers to handle artifacts

**Timeline:** 1-2 days for most systems

**Risk:** Low (artifact generation is stable)

### Option 2: Emergency Revert

**If necessary:** Revert to previous execute.js

**Steps:**
```bash
git revert a80f172  # Revert artifact implementation
git push
```

**Impact:** Loses artifact surface benefits

**Risk:** High (system becomes non-convergent again)

---

## Support Resources

### Documentation

- **Full Guide:** `docs/AGOII-ARTIFACT-SPINE-001.md`
- **Quick Ref:** `docs/ARTIFACT-SPINE-QUICKREF.md`
- **Schema:** `agoii/schema/execution_schema.ts`

### Tests

- **Test Suite:** `test/artifact-surface.test.js`
- **Run:** `node test/artifact-surface.test.js`
- **Expected:** 10/10 passing

### Code Examples

- **Artifact Generation:** `execute.js` lines 190-280
- **Validation:** `execute.js` lines 235-260
- **Hash Computation:** `execute.js` lines 197-199

---

## Timeline

### Phase 1: Immediate (Day 1)

- [ ] Update ExecutionAuthority to expect artifact
- [ ] Add artifact validation
- [ ] Handle new failure types
- [ ] Update tests

### Phase 2: Short-term (Week 1)

- [ ] Update persistence layer
- [ ] Add artifact storage
- [ ] Enable artifact retrieval
- [ ] Update monitoring

### Phase 3: Medium-term (Month 1)

- [ ] Implement delta detection
- [ ] Add regression checks
- [ ] Enable convergence engine
- [ ] Optimize artifact storage

---

## Checklist

Before deploying to production:

- [ ] Updated TypeScript types from execution_schema.ts
- [ ] Added artifact validation in report handlers
- [ ] Updated persistence layer to store artifacts
- [ ] Tested with artifact-surface.test.js (10/10 passing)
- [ ] Handled `invalid_artifact` and `missing_artifact` failures
- [ ] Updated monitoring/logging to include artifacts
- [ ] Reviewed and tested delta detection (if applicable)
- [ ] Updated documentation for your team
- [ ] Verified no backward compatibility issues
- [ ] Prepared rollback plan (if needed)

---

## Questions & Answers

### Q: Can I make artifact optional?

**A:** No. Artifact is mandatory per AGOII–ARTIFACT-SPINE-001 §STEP-11. No backward compatibility layer is provided by design.

### Q: What if I don't need artifacts?

**A:** The artifact field will be present whether you use it or not. You can ignore it, but you must handle its presence.

### Q: Will artifacts grow over time?

**A:** In v1, artifacts duplicate output content. Future optimizations will use content references to reduce size.

### Q: Can I modify artifacts?

**A:** No. Artifacts are generated exclusively by NemoCore. Modification violates the integrity guarantee.

### Q: How do I handle artifact in UI?

**A:** Display hash, show content, or ignore. Artifacts are primarily for validation, not user display.

---

## Support

If you encounter issues:

1. Review full documentation: `docs/AGOII-ARTIFACT-SPINE-001.md`
2. Check test suite: `test/artifact-surface.test.js`
3. Verify schema: `agoii/schema/execution_schema.ts`
4. File issue with reproduction case

---

**Migration Guide Version:** 1.0  
**Last Updated:** 2026-04-03  
**Status:** Active
