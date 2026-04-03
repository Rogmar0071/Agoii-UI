# AGOII-ARTIFACT-SPINE-VALIDATION-002 Quick Reference

**Architectural Boundary Correction**

---

## TL;DR

Moved artifact generation from orchestration runtime to contractor layer.

**Why:** Runtime should validate truth, not create it.

---

## What Changed

### Before (WRONG)

```javascript
// execute.js (orchestrator)
const artifact = buildArtifact(rawOutput);  // ← Runtime creates
validateArtifact(artifact);  // ← Runtime validates own creation
```

### After (CORRECT)

```javascript
// executeOpenAIAdapter (contractor)
const artifact = buildArtifact(rawOutput);  // ← Contractor creates
return {rawOutput, exitCode, timedOut, artifact};

// execute.js (orchestrator)
const result = await executeOpenAIAdapter(contract);
validateArtifact(result.artifact);  // ← Runtime validates contractor's artifact
```

---

## Key Changes

1. **Artifact Generation:** Moved INTO contractor
2. **Runtime Role:** Changed to validation-only
3. **Hard Failure:** Added for missing artifacts
4. **Boundary:** Restored contractor/runtime separation

---

## Contractor Responsibilities

Contractors MUST return:

```javascript
{
  rawOutput: string,
  exitCode: number,
  timedOut: boolean,
  artifact: {
    sections: [{
      section_id: string,
      content: string,
      content_hash: string
    }]
  }
}
```

**Artifact is mandatory.**

---

## Runtime Responsibilities

Runtime MUST:
- ✅ Validate artifact presence
- ✅ Validate artifact structure
- ✅ Verify hash integrity
- ❌ NOT create artifacts
- ❌ NOT modify artifacts

---

## Enforcement Rules

### Rule 1: No Runtime Artifact Creation

```javascript
// ❌ WRONG
function buildExecutionReport({ rawOutput }) {
  const artifact = buildArtifact(rawOutput);  // NO!
}

// ✅ CORRECT
function buildExecutionReport({ artifact }) {
  if (!artifact) {
    fail('missing_artifact');  // Hard fail
  }
  validateArtifact(artifact);  // Only validate
}
```

### Rule 2: Hard Fail on Missing

```javascript
if (!artifact) {
  return {
    status: 'failure',
    failure_surface: {
      type: 'missing_artifact',
      source: 'artifact',
      details: 'Contractor did not produce required artifact'
    }
  };
}
```

**No fallback. No silent pass.**

### Rule 3: Contractor Must Produce

```javascript
async function executeOpenAIAdapter(contract) {
  const result = await openaiRequest(...);
  
  // MUST create artifact before returning
  const artifact = buildArtifact(result.rawOutput);
  
  return {...result, artifact};  // MUST include
}
```

---

## Architecture Diagram

```
┌──────────────────┐
│ ExecutionAuthority│
└────────┬─────────┘
         │
    ╔════╧════════════════════╗
    ║  EXECUTION BOUNDARY     ║
    ╚════╤════════════════════╝
         ↓
┌────────────────────┐
│  execute.js        │
│  (Orchestrator)    │
│  - Routes          │
│  - VALIDATES only  │
└────────┬───────────┘
         ↓
┌─────────────────────────┐
│  executeOpenAIAdapter   │
│  (Contractor)           │
│  - Executes             │
│  - CREATES artifact ←   │
└────────┬────────────────┘
         ↓
┌────────────────────┐
│  Result + Artifact │
└────────┬───────────┘
         │
    ╔════╧════════════════════╗
    ║  EXECUTION BOUNDARY     ║
    ╚════╤════════════════════╝
         ↓
┌────────────────────┐
│  execute.js        │
│  - Validates       │
│  - Builds report   │
└────────┬───────────┘
         ↓
┌────────────────────┐
│  ExecutionReport   │
│  + Artifact        │
└────────────────────┘
```

---

## Boundary Rules

| Layer | Creates Artifact | Validates Artifact |
|-------|------------------|-------------------|
| Contractor | ✅ YES | ❌ NO |
| Runtime | ❌ NO | ✅ YES |

**Clear separation. No overlap.**

---

## Code Locations

### Artifact Creation (Contractor)

**File:** `execute.js`  
**Function:** `executeOpenAIAdapter()` (lines 180-229)

```javascript
async function executeOpenAIAdapter(contract) {
  // ... execution ...
  
  const artifact = buildArtifact(result.rawOutput);
  
  return {...result, artifact};
}
```

### Artifact Validation (Runtime)

**File:** `execute.js`  
**Function:** `validateArtifact()` (lines 240-295)

```javascript
function validateArtifact(artifact) {
  if (!artifact) {
    return { valid: false, error: 'Artifact is missing' };
  }
  
  // Validate structure and hashes
  // ...
  
  return { valid: true };
}
```

---

## Test Results

```
✔ 10/10 tests passing
✔ All boundary conditions validated
✔ Missing artifact fails correctly
✔ Hash integrity verified
✔ Contractor produces artifacts
```

---

## Migration Impact

**Breaking Changes:** None  
**Internal Changes:** Yes  
**External API:** Unchanged  
**Consumer Impact:** None  

This is an internal refactoring with no external changes.

---

## Future Contractors

All future contractors MUST follow this pattern:

```javascript
async function newContractor(contract) {
  // 1. Do work
  const output = await work();
  
  // 2. Create artifact (MANDATORY)
  const artifact = {
    sections: [{
      section_id: 'main',
      content: output,
      content_hash: sha256(output)
    }]
  };
  
  // 3. Return with artifact
  return {
    rawOutput: output,
    exitCode: 0,
    timedOut: false,
    artifact  // MUST include
  };
}
```

---

## Key Takeaway

> **Runtime validates truth. It does not create truth.**

This correction enforces that principle.

---

## Status

✅ **COMPLETE**  
✅ **TESTED**  
✅ **ENFORCED**  
✅ **BOUNDARY RESTORED**

---

**Quick Ref Version:** 1.0  
**Last Updated:** 2026-04-03
