# AGOII Execution Observation

This directory contains the execution observation script and results for AGOII–EXECUTION-OBSERVATION-001.

## Running the Observation

### Without API Key (Simulated Failures Only)

```bash
node scripts/observe_execution.js > observation_output.json 2> observation_log.txt
```

This will test:
- Contract validation
- Failure surface structure
- Replay consistency
- Load stability
- Driver cleanup

### With API Key (Full Validation)

```bash
export OPENAI_API_KEY="your-key-here"
node scripts/observe_execution.js > observation_output.json 2> observation_log.txt
```

This additionally tests:
- Real OpenAI API execution
- Success path validation
- Actual network timeouts
- Output parsing

## Output Files

- **observation_output.json** — Structured JSON report with metrics and findings
- **observation_log.txt** — Human-readable log of test execution
- **docs/AGOII-EXECUTION-OBSERVATION-001-REPORT.md** — Comprehensive analysis report
- **docs/observation_report.json** — Raw observation data

## Test Scenarios

The observation script executes the following test cases:

1. **STEP-1:** Success path (requires API key)
2. **STEP-3A:** Missing API key failure
3. **STEP-3B:** Timeout failure
4. **STEP-3C:** Invalid contractor rejection
5. **STEP-3D:** Malformed contract rejection
6. **STEP-5:** Replay consistency (2 executions)
7. **STEP-6:** Load test (10 sequential executions)
8. **STEP-7:** Driver validation (temp file cleanup, JSON output)
9. **STEP-8:** Metric aggregation and analysis

## Validation Criteria

The script validates that:

- ✓ All reports are valid JSON
- ✓ execution_id is preserved in responses
- ✓ All non-success statuses include failure_surface
- ✓ Failure surfaces have type/source/details
- ✓ Metadata includes contractor_id, durationMs, timestamp
- ✓ Temp files are cleaned up
- ✓ Schema structure is consistent across replays
- ✓ System remains stable under load

## Example Output

```json
{
  "observation_id": "AGOII–EXECUTION-OBSERVATION-001",
  "summary": {
    "system_status": "STABLE",
    "total_executions": 16,
    "anomaly_count": 1
  },
  "metrics": {
    "total_executions": 16,
    "success_rate_pct": "0.00",
    "failure_rate_pct": "87.50",
    "rejection_rate_pct": "12.50",
    "latency": {
      "avg_ms": 39.13,
      "max_ms": 42,
      "min_ms": 37
    }
  }
}
```

## Anomalies

Any deviations from expected behavior are recorded in the `anomalies` array:

```json
{
  "anomalies": [
    {
      "execution_id": "obs-timeout-001",
      "description": "STEP-3B: Timeout",
      "issue": "Status mismatch: expected timeout, got failure"
    }
  ]
}
```

## Notes

- The observation script does NOT modify any code
- All tests are deterministic and repeatable
- Temporary files are created in `/tmp/agoii-obs/` and cleaned up automatically
- The script exits with code 0 if stable, 1 if anomalies detected
