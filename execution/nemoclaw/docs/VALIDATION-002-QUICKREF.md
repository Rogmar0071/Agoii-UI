# VALIDATION-002 Quick Reference

**AGOII–EXECUTION-OBSERVATION-VALIDATION-002**  
**Final Stability Gate — Success Path Activation**

---

## TL;DR

**What:** Validate real OpenAI execution (success path)  
**Why:** Only remaining validation criterion before system is fully operational  
**How:** Run script with OPENAI_API_KEY set

---

## Quick Start

```bash
# 1. Set API key
export OPENAI_API_KEY="sk-your-key-here"

# 2. Run validation
cd /home/runner/work/NemoClaw/NemoClaw
node scripts/validate_success_path.js > validation_report.json 2> validation_log.txt

# 3. Check result
cat validation_report.json | jq '.status'
# Expected: "PASS"
```

---

## What Gets Validated

- ✓ Basic success execution
- ✓ Short prompt handling
- ✓ Longer timeout handling
- ✓ Response parsing
- ✓ Schema compliance

---

## Expected Output

```json
{
  "validation_id": "AGOII–EXECUTION-OBSERVATION-VALIDATION-002",
  "status": "PASS",
  "metrics": {
    "total_attempts": 3,
    "successes": 3,
    "failures": 0
  }
}
```

---

## Success = All Tests PASS

If `status: "PASS"` → System is **FULLY OPERATIONAL**

---

## Common Issues

| Problem | Solution |
|---------|----------|
| No API key | `export OPENAI_API_KEY="sk-..."` |
| Invalid key | Get new key from platform.openai.com |
| Rate limit | Wait and retry |
| Network error | Check connectivity to api.openai.com |

---

## Files

- **Script:** `scripts/validate_success_path.js`
- **Full Guide:** `docs/AGOII-VALIDATION-002-GUIDE.md`
- **Previous Validation:** `docs/AGOII-EXECUTION-OBSERVATION-001-SUMMARY.md`

---

## After Validation Passes

1. Update docs to mark success path as validated
2. Change system state: PARTIALLY VALIDATED → FULLY VALIDATED
3. System transitions: OBSERVE → READY
4. Production deployment authorized

---

**Remember:** This validation requires a REAL API key. No mocks, no simulations.
