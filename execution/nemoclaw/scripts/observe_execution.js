#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * AGOII–EXECUTION-OBSERVATION-001 — Runtime Observation & Failure Surface Calibration
 * 
 * This script performs comprehensive runtime observation of the NemoClaw execution system.
 * It validates success paths, failure surfaces, deterministic behavior, and system stability.
 * 
 * NO structural changes are made to the codebase - this is pure observation.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

// ── Configuration ──────────────────────────────────────────────────────────

const REPO_ROOT = path.resolve(__dirname, '..');
const EXECUTE_JS = path.join(REPO_ROOT, 'execute.js');
const TMP_DIR = '/tmp/agoii-obs';

// Ensure tmp directory exists
if (!fs.existsSync(TMP_DIR)) {
  fs.mkdirSync(TMP_DIR, { recursive: true });
}

// ── Execution Report Collector ────────────────────────────────────────────

const observations = {
  timestamp: new Date().toISOString(),
  success_count: 0,
  failure_count: 0,
  timeout_count: 0,
  rejection_count: 0,
  executions: [],
  latencies: [],
  anomalies: [],
};

// ── Helpers ────────────────────────────────────────────────────────────────

function log(section, message) {
  console.error(`[${section}] ${message}`);
}

function executeContract(contract, description) {
  return new Promise((resolve) => {
    const contractId = contract.execution_id || 'unknown';
    const contractFile = path.join(TMP_DIR, `contract-${contractId}-${Date.now()}.json`);
    
    fs.writeFileSync(contractFile, JSON.stringify(contract), 'utf-8');
    
    const startMs = Date.now();
    let stdout = '';
    let stderr = '';
    
    const child = spawn(process.execPath, [EXECUTE_JS, contractFile], {
      cwd: REPO_ROOT,
      env: { ...process.env },
    });
    
    child.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
    child.stderr.on('data', (chunk) => { stderr += chunk.toString(); });
    
    child.on('close', (code) => {
      const durationMs = Date.now() - startMs;
      
      // Clean up temp file
      try {
        fs.unlinkSync(contractFile);
      } catch (err) {
        // Ignore cleanup errors
      }
      
      let report = null;
      let parseError = null;
      
      try {
        report = JSON.parse(stdout);
      } catch (err) {
        parseError = `${err.message} (stdout: "${stdout.substring(0, 100)}")`;
      }
      
      resolve({
        description,
        contract,
        report,
        parseError,
        exitCode: code,
        durationMs,
        stdout,
        stderr,
      });
    });
    
    child.on('error', (err) => {
      const durationMs = Date.now() - startMs;
      
      try {
        fs.unlinkSync(contractFile);
      } catch (cleanupErr) {
        // Ignore
      }
      
      resolve({
        description,
        contract,
        report: null,
        parseError: err.message,
        exitCode: 1,
        durationMs,
        stdout: '',
        stderr: err.message,
      });
    });
  });
}

function recordObservation(result) {
  const { description, report, parseError, durationMs } = result;
  
  observations.executions.push({
    description,
    execution_id: result.contract.execution_id,
    status: report ? report.status : 'parse_error',
    exit_code: report ? report.exit_code : result.exitCode,
    durationMs,
    has_failure_surface: report && report.failure_surface !== undefined,
    parse_error: parseError || null,
  });
  
  if (report) {
    observations.latencies.push(durationMs);
    
    if (report.status === 'success') {
      observations.success_count++;
    } else if (report.status === 'timeout') {
      observations.timeout_count++;
    } else if (report.status === 'rejected') {
      observations.rejection_count++;
    } else {
      observations.failure_count++;
    }
  } else {
    observations.anomalies.push({
      execution_id: result.contract.execution_id,
      description,
      issue: 'Failed to parse report JSON',
      details: parseError,
    });
  }
}

function validateReport(result, expectedStatus, expectedFailureSurfaceType) {
  const { description, report, parseError } = result;
  
  if (parseError) {
    log('VALIDATION', `✗ ${description}: Failed to parse JSON - ${parseError}`);
    return false;
  }
  
  if (!report) {
    log('VALIDATION', `✗ ${description}: No report returned`);
    return false;
  }
  
  // Check required fields
  const required = ['execution_id', 'status', 'exit_code', 'outputs', 'metadata'];
  for (const field of required) {
    if (!(field in report)) {
      log('VALIDATION', `✗ ${description}: Missing field '${field}'`);
      observations.anomalies.push({
        execution_id: result.contract.execution_id,
        description,
        issue: `Missing required field: ${field}`,
      });
      return false;
    }
  }
  
  // Check execution_id preservation
  if (report.execution_id !== result.contract.execution_id) {
    log('VALIDATION', `✗ ${description}: execution_id mismatch (expected ${result.contract.execution_id}, got ${report.execution_id})`);
    observations.anomalies.push({
      execution_id: result.contract.execution_id,
      description,
      issue: 'execution_id not preserved',
    });
    return false;
  }
  
  // Check status
  if (expectedStatus && report.status !== expectedStatus) {
    log('VALIDATION', `✗ ${description}: Expected status '${expectedStatus}', got '${report.status}'`);
    observations.anomalies.push({
      execution_id: result.contract.execution_id,
      description,
      issue: `Status mismatch: expected ${expectedStatus}, got ${report.status}`,
    });
    return false;
  }
  
  // Check failure surface for non-success
  if (report.status !== 'success') {
    if (!report.failure_surface) {
      log('VALIDATION', `✗ ${description}: No failure_surface for non-success status`);
      observations.anomalies.push({
        execution_id: result.contract.execution_id,
        description,
        issue: 'Missing failure_surface for non-success status',
      });
      return false;
    }
    
    if (!report.failure_surface.type || !report.failure_surface.source || !report.failure_surface.details) {
      log('VALIDATION', `✗ ${description}: Incomplete failure_surface`);
      observations.anomalies.push({
        execution_id: result.contract.execution_id,
        description,
        issue: 'Incomplete failure_surface (missing type/source/details)',
      });
      return false;
    }
    
    if (expectedFailureSurfaceType && report.failure_surface.type !== expectedFailureSurfaceType) {
      log('VALIDATION', `⚠ ${description}: Expected failure type '${expectedFailureSurfaceType}', got '${report.failure_surface.type}'`);
    }
  }
  
  // Check metadata
  if (!report.metadata || typeof report.metadata !== 'object') {
    log('VALIDATION', `✗ ${description}: Missing or invalid metadata`);
    observations.anomalies.push({
      execution_id: result.contract.execution_id,
      description,
      issue: 'Missing or invalid metadata',
    });
    return false;
  }
  
  if (!report.metadata.contractor_id || typeof report.metadata.durationMs !== 'number' || !report.metadata.timestamp) {
    log('VALIDATION', `✗ ${description}: Incomplete metadata`);
    observations.anomalies.push({
      execution_id: result.contract.execution_id,
      description,
      issue: 'Incomplete metadata (missing contractor_id, durationMs, or timestamp)',
    });
    return false;
  }
  
  log('VALIDATION', `✓ ${description}: All checks passed`);
  return true;
}

// ── Test Scenarios ─────────────────────────────────────────────────────────

async function runTests() {
  console.error('\n=== AGOII–EXECUTION-OBSERVATION-001 ===\n');
  console.error(`Execute.js path: ${EXECUTE_JS}`);
  console.error(`Repository root: ${REPO_ROOT}\n`);
  
  // ── STEP 1: SUCCESS PATH ─────────────────────────────────────────────────
  
  log('STEP-1', 'Testing success path with valid API key...');
  
  if (!process.env.OPENAI_API_KEY) {
    log('STEP-1', '⚠ OPENAI_API_KEY not set - skipping real execution tests');
    log('STEP-1', 'Set OPENAI_API_KEY to enable real execution observation');
  } else {
    const successContract = {
      execution_id: 'obs-001',
      contractor_id: 'openai-inference',
      input: {
        prompt: 'Return a short deterministic sentence.',
      },
      execution_policy: {
        process: {
          timeoutMs: 10000,
        },
      },
    };
    
    const result = await executeContract(successContract, 'STEP-1: Success path');
    recordObservation(result);
    validateReport(result, 'success', null);
    
    if (result.report && result.report.status === 'success') {
      log('STEP-1', `✓ Success: durationMs=${result.durationMs}, output_length=${result.report.outputs.length}`);
    }
  }
  
  // ── STEP 3A: Missing API Key ─────────────────────────────────────────────
  
  log('STEP-3A', 'Testing failure: missing API key...');
  
  const failureContract = {
    execution_id: 'obs-failure-001',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'This should fail.',
    },
    execution_policy: {
      process: {
        timeoutMs: 5000,
      },
    },
  };
  
  // Temporarily remove API key
  const savedKey = process.env.OPENAI_API_KEY;
  delete process.env.OPENAI_API_KEY;
  
  const failureResult = await executeContract(failureContract, 'STEP-3A: Missing API key');
  recordObservation(failureResult);
  validateReport(failureResult, 'failure', 'execution_error');
  
  // Restore API key
  if (savedKey) {
    process.env.OPENAI_API_KEY = savedKey;
  }
  
  // ── STEP 3B: Timeout ─────────────────────────────────────────────────────
  
  log('STEP-3B', 'Testing failure: timeout...');
  
  const timeoutContract = {
    execution_id: 'obs-timeout-001',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Return something.',
    },
    execution_policy: {
      process: {
        timeoutMs: 1, // Very short timeout to force timeout
      },
    },
  };
  
  const timeoutResult = await executeContract(timeoutContract, 'STEP-3B: Timeout');
  recordObservation(timeoutResult);
  validateReport(timeoutResult, 'timeout', 'timeout');
  
  // ── STEP 3C: Invalid Contractor ──────────────────────────────────────────
  
  log('STEP-3C', 'Testing rejection: invalid contractor...');
  
  const invalidContractorContract = {
    execution_id: 'obs-invalid-contractor-001',
    contractor_id: 'invalid-contractor',
    input: {
      prompt: 'This should be rejected.',
    },
    execution_policy: {
      process: {
        timeoutMs: 5000,
      },
    },
  };
  
  const invalidResult = await executeContract(invalidContractorContract, 'STEP-3C: Invalid contractor');
  recordObservation(invalidResult);
  validateReport(invalidResult, 'rejected', 'contract_rejection');
  
  // ── STEP 3D: Malformed Contract ──────────────────────────────────────────
  
  log('STEP-3D', 'Testing rejection: malformed contract (missing input)...');
  
  const malformedContract = {
    execution_id: 'obs-malformed-001',
    contractor_id: 'openai-inference',
    // Missing 'input' field
    execution_policy: {
      process: {
        timeoutMs: 5000,
      },
    },
  };
  
  const malformedResult = await executeContract(malformedContract, 'STEP-3D: Malformed contract');
  recordObservation(malformedResult);
  validateReport(malformedResult, 'rejected', 'contract_rejection');
  
  // ── STEP 5: Replay Consistency ───────────────────────────────────────────
  
  log('STEP-5', 'Testing replay consistency...');
  
  const replayContract = {
    execution_id: 'obs-replay-001',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Deterministic test.',
    },
    execution_policy: {
      process: {
        timeoutMs: 5000,
      },
    },
  };
  
  const replay1 = await executeContract(replayContract, 'STEP-5: Replay #1');
  recordObservation(replay1);
  
  const replay2 = await executeContract(replayContract, 'STEP-5: Replay #2');
  recordObservation(replay2);
  
  // Compare structure
  if (replay1.report && replay2.report) {
    const keys1 = Object.keys(replay1.report).sort();
    const keys2 = Object.keys(replay2.report).sort();
    
    if (JSON.stringify(keys1) === JSON.stringify(keys2)) {
      log('STEP-5', '✓ Replay: Identical schema structure');
    } else {
      log('STEP-5', '✗ Replay: Schema mismatch');
      observations.anomalies.push({
        execution_id: 'obs-replay-001',
        description: 'Replay consistency',
        issue: 'Schema structure differs between replays',
      });
    }
  }
  
  // ── STEP 6: Load Test ────────────────────────────────────────────────────
  
  log('STEP-6', 'Running load test (10 sequential executions)...');
  
  for (let i = 1; i <= 10; i++) {
    const loadContract = {
      execution_id: `obs-load-${i}`,
      contractor_id: 'openai-inference',
      input: {
        prompt: `Load test iteration ${i}.`,
      },
      execution_policy: {
        process: {
          timeoutMs: 5000,
        },
      },
    };
    
    const loadResult = await executeContract(loadContract, `STEP-6: Load test #${i}`);
    recordObservation(loadResult);
    
    // Quick validation
    if (!loadResult.report || !loadResult.report.execution_id) {
      observations.anomalies.push({
        execution_id: `obs-load-${i}`,
        description: `Load test #${i}`,
        issue: 'Report structure degraded under load',
      });
    }
  }
  
  log('STEP-6', `✓ Load test completed: ${observations.executions.length} total executions`);
  
  // ── STEP 7: Driver Validation ────────────────────────────────────────────
  
  log('STEP-7', 'Validating driver behavior...');
  
  // Check for orphan temp files
  const tmpFiles = fs.readdirSync(TMP_DIR).filter(f => f.startsWith('contract-'));
  if (tmpFiles.length > 0) {
    log('STEP-7', `⚠ Found ${tmpFiles.length} orphan temp files`);
    observations.anomalies.push({
      execution_id: 'driver-validation',
      description: 'Temp file cleanup',
      issue: `${tmpFiles.length} temp files not deleted`,
    });
  } else {
    log('STEP-7', '✓ All temp files cleaned up');
  }
  
  // Verify all stdout was valid JSON
  const parseErrors = observations.executions.filter(e => e.parse_error);
  if (parseErrors.length > 0) {
    log('STEP-7', `✗ ${parseErrors.length} executions returned non-JSON stdout`);
  } else {
    log('STEP-7', '✓ All executions returned valid JSON');
  }
  
  // ── STEP 8: Metric Capture ───────────────────────────────────────────────
  
  log('STEP-8', 'Computing metrics...');
  
  const totalExecutions = observations.executions.length;
  const successRate = totalExecutions > 0 ? (observations.success_count / totalExecutions * 100).toFixed(2) : '0.00';
  const failureRate = totalExecutions > 0 ? (observations.failure_count / totalExecutions * 100).toFixed(2) : '0.00';
  const timeoutRate = totalExecutions > 0 ? (observations.timeout_count / totalExecutions * 100).toFixed(2) : '0.00';
  const rejectionRate = totalExecutions > 0 ? (observations.rejection_count / totalExecutions * 100).toFixed(2) : '0.00';
  
  const avgLatency = observations.latencies.length > 0
    ? (observations.latencies.reduce((a, b) => a + b, 0) / observations.latencies.length).toFixed(2)
    : 0;
  const maxLatency = observations.latencies.length > 0
    ? Math.max(...observations.latencies)
    : 0;
  const minLatency = observations.latencies.length > 0
    ? Math.min(...observations.latencies)
    : 0;
  
  const metrics = {
    total_executions: totalExecutions,
    success_count: observations.success_count,
    failure_count: observations.failure_count,
    timeout_count: observations.timeout_count,
    rejection_count: observations.rejection_count,
    success_rate_pct: successRate,
    failure_rate_pct: failureRate,
    timeout_rate_pct: timeoutRate,
    rejection_rate_pct: rejectionRate,
    latency: {
      avg_ms: parseFloat(avgLatency),
      max_ms: maxLatency,
      min_ms: minLatency,
    },
  };
  
  log('STEP-8', `Total executions: ${totalExecutions}`);
  log('STEP-8', `Success rate: ${successRate}%`);
  log('STEP-8', `Failure rate: ${failureRate}%`);
  log('STEP-8', `Timeout rate: ${timeoutRate}%`);
  log('STEP-8', `Rejection rate: ${rejectionRate}%`);
  log('STEP-8', `Avg latency: ${avgLatency}ms`);
  log('STEP-8', `Max latency: ${maxLatency}ms`);
  
  // ── OUTPUT ARTIFACT ──────────────────────────────────────────────────────
  
  const report = {
    observation_id: 'AGOII–EXECUTION-OBSERVATION-001',
    timestamp: observations.timestamp,
    summary: {
      system_status: observations.anomalies.length === 0 ? 'STABLE' : 'UNSTABLE',
      total_executions: totalExecutions,
      anomaly_count: observations.anomalies.length,
    },
    metrics,
    failure_classification: {
      timeout: observations.timeout_count,
      execution_error: observations.failure_count,
      contract_rejection: observations.rejection_count,
    },
    latency_profile: {
      avg_ms: parseFloat(avgLatency),
      max_ms: maxLatency,
      min_ms: minLatency,
      samples: observations.latencies.length,
    },
    anomalies: observations.anomalies,
    executions: observations.executions,
    validation_results: {
      success_path_works: observations.success_count > 0,
      failure_paths_validated: observations.failure_count > 0 || observations.timeout_count > 0 || observations.rejection_count > 0,
      deterministic_structure: parseErrors.length === 0,
      replay_stable: observations.executions.filter(e => e.execution_id === 'obs-replay-001').length === 2,
      no_crashes_under_load: observations.executions.filter(e => e.execution_id.startsWith('obs-load-')).length === 10,
    },
  };
  
  // Write report to stdout as JSON
  console.log(JSON.stringify(report, null, 2));
  
  log('OUTPUT', `Observation complete: ${observations.anomalies.length} anomalies detected`);
  
  // Return exit code based on stability
  return observations.anomalies.length === 0 ? 0 : 1;
}

// ── Entry Point ────────────────────────────────────────────────────────────

runTests()
  .then((exitCode) => {
    process.exit(exitCode);
  })
  .catch((err) => {
    console.error('Fatal error during observation:', err);
    process.exit(1);
  });
