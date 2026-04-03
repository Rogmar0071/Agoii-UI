# Artifact Surface Quick Reference

**AGOII–ARTIFACT-SPINE-001 Implementation**

---

## TL;DR

Every NemoClaw execution report now includes a **deterministic artifact** with SHA-256 hashed sections.

**No artifact = execution fails**

---

## Artifact Structure

```json
{
  "artifact": {
    "sections": [
      {
        "section_id": "main",
        "content": "output text",
        "content_hash": "sha256 hex (64 chars)"
      }
    ]
  }
}
```

---

## Key Rules

1. **Artifact MUST be present** in every report
2. **At least one section** required (except rejections)
3. **Hash MUST match** SHA-256 of content
4. **Invalid artifact** → execution fails

---

## Default Strategy

- **One section** with ID "main"
- **Content:** Full raw output
- **Hash:** SHA-256(content)

---

## Validation

```javascript
// Check artifact presence
if (!report.artifact || !report.artifact.sections) {
  // Invalid report
}

// Check section count
if (report.artifact.sections.length === 0 && report.status !== 'rejected') {
  // Invalid (non-rejection with empty artifact)
}

// Verify hash integrity
import crypto from 'crypto';
const expectedHash = crypto.createHash('sha256')
  .update(section.content, 'utf8')
  .digest('hex');
if (section.content_hash !== expectedHash) {
  // Hash mismatch - artifact corrupted
}
```

---

## Failure Types

### `invalid_artifact`

```json
{
  "status": "failure",
  "failure_surface": {
    "type": "invalid_artifact",
    "source": "artifact",
    "details": "Section 0 hash mismatch"
  }
}
```

**Causes:**
- Missing sections array
- Empty sections on execution
- Hash mismatch
- Invalid structure

---

## Testing

```bash
node test/artifact-surface.test.js
```

**Expected:** 10/10 tests passing

---

## Migration

### Breaking Change

All reports now include `artifact` field.

**Update your code:**

1. Expect `artifact` field in all reports
2. Validate artifact presence
3. Handle `invalid_artifact` failures
4. Store artifacts for delta comparison

**No fallback** — System fails if artifact missing (by design)

---

## Schema Types

```typescript
interface ArtifactSection {
  section_id: string;
  content: string;
  content_hash: string;  // SHA-256 hex
}

interface ExecutionArtifact {
  sections: ArtifactSection[];
}

interface NemoClawReport {
  // ... other fields ...
  artifact: ExecutionArtifact;  // MANDATORY
}
```

---

## Examples

### Success

```json
{
  "execution_id": "example-001",
  "status": "success",
  "exit_code": 0,
  "artifact": {
    "sections": [{
      "section_id": "main",
      "content": "Hello, World!",
      "content_hash": "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"
    }]
  }
}
```

### Rejection

```json
{
  "execution_id": "example-002",
  "status": "rejected",
  "exit_code": 1,
  "artifact": {
    "sections": []  // Empty on rejection OK
  },
  "failure_surface": {
    "type": "contract_rejection",
    "source": "validator",
    "details": "Invalid contractor_id"
  }
}
```

### Artifact Failure

```json
{
  "execution_id": "example-003",
  "status": "failure",
  "exit_code": 1,
  "artifact": {
    "sections": []  // Empty due to validation failure
  },
  "failure_surface": {
    "type": "invalid_artifact",
    "source": "artifact",
    "details": "Hash integrity check failed"
  }
}
```

---

## Hash Computation

```javascript
const crypto = require('crypto');

function computeHash(content) {
  return crypto.createHash('sha256')
    .update(content, 'utf8')
    .digest('hex');
}

// Example
const hash = computeHash('Hello, World!');
// → "dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f"
```

---

## Delta Detection

With artifacts, you can now:

```javascript
// Compare two executions
const hash1 = report1.artifact.sections[0].content_hash;
const hash2 = report2.artifact.sections[0].content_hash;

if (hash1 === hash2) {
  // Identical output (deterministic)
} else {
  // Output changed (regression or intended)
}
```

---

## Files

- **Schema:** `agoii/schema/execution_schema.ts`
- **Implementation:** `execute.js`
- **Tests:** `test/artifact-surface.test.js`
- **Docs:** `docs/AGOII-ARTIFACT-SPINE-001.md`

---

## Status

✅ **COMPLETE** — All tests passing  
✅ **OPERATIONAL** — Production ready  
✅ **MANDATORY** — No fallback

---

**Quick Ref Version:** 1.0  
**Last Updated:** 2026-04-03
