# AGOII–EXECUTION-OBSERVATION-001 — Running With Real API Key

This guide explains how to complete the observation with a real OpenAI API key to validate the success path.

---

## PREREQUISITES

- Valid OpenAI API key with access to GPT-4o model
- Network connectivity to api.openai.com
- Node.js >= 20.0.0

---

## STEP 1: Set API Key

### Option A: Environment Variable (Recommended)

```bash
export OPENAI_API_KEY="sk-your-actual-key-here"
```

### Option B: Shell Session

```bash
OPENAI_API_KEY="sk-your-actual-key-here" node scripts/observe_execution.js
```

---

## STEP 2: Run Observation

```bash
cd /path/to/NemoClaw
node scripts/observe_execution.js > observation_with_api_key.json 2> observation_log_api.txt
```

---

## STEP 3: Review Results

```bash
# Check summary
cat observation_log_api.txt | grep "STEP-"

# Check metrics
cat observation_with_api_key.json | jq '.metrics'

# Check success rate
cat observation_with_api_key.json | jq '.metrics.success_rate_pct'

# Check for anomalies
cat observation_with_api_key.json | jq '.anomalies'
```

---

## EXPECTED RESULTS WITH API KEY

### Success Path (STEP-1)

- **Status:** success
- **exit_code:** 0
- **outputs:** Contains OpenAI response
- **Duration:** Typically 500-3000ms (depends on API latency)

### Missing API Key Test (STEP-3A)

- **Status:** failure (API key temporarily removed during test)
- **failure_surface.type:** execution_error
- **failure_surface.details:** "Non-zero exit code: 1"

### Timeout Test (STEP-3B)

With API key, a 1ms timeout should still result in:
- **Status:** failure or timeout (depends on exact timing)
- **Reason:** Network request cannot complete in 1ms

### Load Test (STEP-6)

All 10 executions should:
- Return successfully (assuming API key is valid)
- Have consistent schema
- No crashes or degradation

---

## VALIDATION CRITERIA (With API Key)

| Criterion | Expected Result |
|-----------|-----------------|
| success_path_works | ✓ PASSED |
| failure_paths_validated | ✓ PASSED |
| deterministic_structure | ✓ PASSED |
| replay_stable | ✓ PASSED |
| no_crashes_under_load | ✓ PASSED |
| success_rate_pct | > 0.00% |
| avg_latency | 500-3000ms (network dependent) |

---

## TROUBLESHOOTING

### Error: "OPENAI_API_KEY not set"

**Solution:** Set the environment variable before running the script.

### Error: "Request failed with status 401"

**Cause:** Invalid API key  
**Solution:** Verify your API key is correct and active.

### Error: "Request failed with status 429"

**Cause:** Rate limit exceeded  
**Solution:** Wait a few moments and retry, or reduce load test volume.

### Timeouts on all requests

**Cause:** Network connectivity issue  
**Solution:** Verify network access to api.openai.com

---

## NOTES

- The observation script temporarily removes the API key during STEP-3A (missing key test)
- The API key is restored immediately after that test
- Success path validation requires approximately 500-3000ms per execution
- Total runtime with API key: ~30-60 seconds (depends on API latency)
- API costs: ~16 requests × $0.000002/token ≈ negligible cost

---

## EXAMPLE OUTPUT (With API Key)

```json
{
  "observation_id": "AGOII–EXECUTION-OBSERVATION-001",
  "summary": {
    "system_status": "STABLE",
    "total_executions": 16,
    "anomaly_count": 0
  },
  "metrics": {
    "success_count": 13,
    "failure_count": 1,
    "rejection_count": 2,
    "success_rate_pct": "81.25",
    "latency": {
      "avg_ms": 1245.67,
      "max_ms": 2987,
      "min_ms": 38
    }
  },
  "validation_results": {
    "success_path_works": true,
    "failure_paths_validated": true,
    "deterministic_structure": true,
    "replay_stable": true,
    "no_crashes_under_load": true
  }
}
```

---

## AFTER COMPLETION

Once observation with API key completes successfully:

1. ✓ Success path validated
2. ✓ Real network behavior observed
3. ✓ Timeout logic verified under load
4. ✓ Output parsing confirmed
5. ✓ System ready for production use

**System transitions from OBSERVE → READY**

---

**Remember:** This is observation only. NO code changes are made.
