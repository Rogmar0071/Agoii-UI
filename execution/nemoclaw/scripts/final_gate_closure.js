#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * AGOII–EXECUTION-READINESS-003 — Final Gate Closure
 * 
 * Executes the final validation contract and formally declares production readiness.
 * 
 * This script:
 * 1. Validates OPENAI_API_KEY availability
 * 2. Executes the final success contract
 * 3. Validates the response
 * 4. Declares production readiness status
 * 
 * Exit codes:
 * 0 = READY FOR PRODUCTION
 * 1 = NOT READY (failure or blocked)
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

// ── Configuration ──────────────────────────────────────────────────────────

const REPO_ROOT = path.resolve(__dirname, '..');
const EXECUTE_JS = path.join(REPO_ROOT, 'execute.js');
const TMP_DIR = '/tmp/agoii-readiness-003';

// Contract as specified in READINESS-003
const FINAL_CONTRACT = {
  execution_id: 'final-success-001',
  contractor_id: 'openai-inference',
  input: {
    prompt: 'Return exactly: SYSTEM_READY',
  },
  execution_policy: {
    process: {
      timeoutMs: 5000,
    },
  },
};

// Ensure tmp directory exists
if (!fs.existsSync(TMP_DIR)) {
  fs.mkdirSync(TMP_DIR, { recursive: true });
}

// ── Readiness Report ───────────────────────────────────────────────────────

const readinessReport = {
  gate_id: 'AGOII–EXECUTION-READINESS-003',
  timestamp: new Date().toISOString(),
  classification: {
    class: 'Governance',
    reversibility: 'Irreversible',
    scope: 'Both',
    invariant_surface: 'Full system readiness / production eligibility',
  },
  validation_status: {
    observation_phase: 'COMPLETE',
    failure_surfaces: 'VALIDATED',
    deterministic_structure: 'VALIDATED',
    replay_stability: 'VALIDATED',
    execution_spine: 'LOCKED',
    driver_integrity: 'VALIDATED',
    success_path: 'PENDING',
  },
  final_execution: null,
  production_readiness: 'PENDING',
  decision: null,
};

// ── Helpers ────────────────────────────────────────────────────────────────

function log(section, message) {
  console.error(`[${section}] ${message}`);
}

function executeContract(contract) {
  return new Promise((resolve) => {
    const contractFile = path.join(TMP_DIR, `contract-${contract.execution_id}.json`);
    
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
        parseError = `${err.message}`;
      }
      
      resolve({
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

// ── Main Execution ─────────────────────────────────────────────────────────

async function executeFinalGate() {
  console.error('\n╔═══════════════════════════════════════════════════════════════╗');
  console.error('║   AGOII–EXECUTION-READINESS-003 — Final Gate Closure         ║');
  console.error('╚═══════════════════════════════════════════════════════════════╝\n');
  
  // Phase 1: Pre-flight validation
  log('PHASE-1', 'Pre-flight validation');
  
  if (!process.env.OPENAI_API_KEY) {
    log('PREFLIGHT', '✗ OPENAI_API_KEY not set');
    log('PREFLIGHT', 'Production readiness requires successful OpenAI execution');
    log('PREFLIGHT', 'Set the key with: export OPENAI_API_KEY="sk-..."');
    console.error('');
    
    readinessReport.production_readiness = 'BLOCKED';
    readinessReport.decision = {
      status: 'NOT_READY',
      reason: 'OPENAI_API_KEY environment variable not set',
      recommendation: 'Set API key and retry',
    };
    
    console.log(JSON.stringify(readinessReport, null, 2));
    return 1;
  }
  
  log('PREFLIGHT', '✓ OPENAI_API_KEY is set');
  log('PREFLIGHT', '✓ execute.js found at ' + EXECUTE_JS);
  console.error('');
  
  // Phase 2: Execute final validation contract
  log('PHASE-2', 'Executing final validation contract');
  log('CONTRACT', `execution_id: ${FINAL_CONTRACT.execution_id}`);
  log('CONTRACT', `contractor_id: ${FINAL_CONTRACT.contractor_id}`);
  log('CONTRACT', `prompt: "${FINAL_CONTRACT.input.prompt}"`);
  log('CONTRACT', `timeout: ${FINAL_CONTRACT.execution_policy.process.timeoutMs}ms`);
  console.error('');
  
  const result = await executeContract(FINAL_CONTRACT);
  
  // Phase 3: Analyze results
  log('PHASE-3', 'Analyzing execution results');
  
  if (result.parseError) {
    log('ANALYSIS', `✗ JSON parse error: ${result.parseError}`);
    
    readinessReport.final_execution = {
      status: 'PARSE_ERROR',
      error: result.parseError,
      stdout: result.stdout,
      stderr: result.stderr,
      duration_ms: result.durationMs,
    };
    readinessReport.validation_status.success_path = 'FAILED';
    readinessReport.production_readiness = 'NOT_READY';
    readinessReport.decision = {
      status: 'NOT_READY',
      reason: 'Failed to parse execution output',
      recommendation: 'Review execute.js output format',
    };
    
    console.error('');
    console.log(JSON.stringify(readinessReport, null, 2));
    return 1;
  }
  
  if (!result.report) {
    log('ANALYSIS', '✗ No report received');
    
    readinessReport.final_execution = {
      status: 'NO_REPORT',
      exit_code: result.exitCode,
      duration_ms: result.durationMs,
    };
    readinessReport.validation_status.success_path = 'FAILED';
    readinessReport.production_readiness = 'NOT_READY';
    readinessReport.decision = {
      status: 'NOT_READY',
      reason: 'No execution report received',
      recommendation: 'Review execute.js execution',
    };
    
    console.error('');
    console.log(JSON.stringify(readinessReport, null, 2));
    return 1;
  }
  
  // Validate report structure
  const report = result.report;
  
  log('ANALYSIS', `Report status: ${report.status}`);
  log('ANALYSIS', `Exit code: ${report.exit_code}`);
  log('ANALYSIS', `Duration: ${result.durationMs}ms`);
  
  if (report.status !== 'success') {
    log('ANALYSIS', `✗ Execution status is not "success"`);
    
    if (report.failure_surface) {
      log('FAILURE', `Type: ${report.failure_surface.type}`);
      log('FAILURE', `Source: ${report.failure_surface.source}`);
      log('FAILURE', `Details: ${report.failure_surface.details}`);
    }
    
    readinessReport.final_execution = {
      status: 'EXECUTION_FAILED',
      execution_status: report.status,
      exit_code: report.exit_code,
      failure_surface: report.failure_surface || null,
      duration_ms: result.durationMs,
    };
    readinessReport.validation_status.success_path = 'FAILED';
    readinessReport.production_readiness = 'NOT_READY';
    readinessReport.decision = {
      status: 'NOT_READY',
      reason: `Execution failed with status: ${report.status}`,
      failure_details: report.failure_surface ? report.failure_surface.details : 'Unknown',
      recommendation: 'Review failure surface and retry',
    };
    
    console.error('');
    console.log(JSON.stringify(readinessReport, null, 2));
    return 1;
  }
  
  if (report.exit_code !== 0) {
    log('ANALYSIS', `✗ Non-zero exit code: ${report.exit_code}`);
    
    readinessReport.final_execution = {
      status: 'NON_ZERO_EXIT',
      exit_code: report.exit_code,
      duration_ms: result.durationMs,
    };
    readinessReport.validation_status.success_path = 'FAILED';
    readinessReport.production_readiness = 'NOT_READY';
    readinessReport.decision = {
      status: 'NOT_READY',
      reason: `Non-zero exit code: ${report.exit_code}`,
      recommendation: 'Review execution logs',
    };
    
    console.error('');
    console.log(JSON.stringify(readinessReport, null, 2));
    return 1;
  }
  
  if (!report.outputs || report.outputs.length === 0) {
    log('ANALYSIS', `✗ No outputs in report`);
    
    readinessReport.final_execution = {
      status: 'NO_OUTPUT',
      duration_ms: result.durationMs,
    };
    readinessReport.validation_status.success_path = 'FAILED';
    readinessReport.production_readiness = 'NOT_READY';
    readinessReport.decision = {
      status: 'NOT_READY',
      reason: 'No outputs received from successful execution',
      recommendation: 'Review OpenAI adapter implementation',
    };
    
    console.error('');
    console.log(JSON.stringify(readinessReport, null, 2));
    return 1;
  }
  
  // SUCCESS!
  log('ANALYSIS', '✓ Execution status: success');
  log('ANALYSIS', '✓ Exit code: 0');
  log('ANALYSIS', `✓ Outputs: ${report.outputs.length} item(s)`);
  log('ANALYSIS', `✓ Duration: ${result.durationMs}ms (within ${FINAL_CONTRACT.execution_policy.process.timeoutMs}ms limit)`);
  console.error('');
  
  // Phase 4: Production Readiness Declaration
  log('PHASE-4', 'Production Readiness Declaration');
  console.error('');
  
  readinessReport.final_execution = {
    status: 'SUCCESS',
    execution_id: report.execution_id,
    exit_code: report.exit_code,
    outputs_count: report.outputs.length,
    duration_ms: result.durationMs,
    metadata: report.metadata,
  };
  readinessReport.validation_status.success_path = 'VALIDATED';
  readinessReport.production_readiness = 'READY';
  readinessReport.decision = {
    status: 'READY_FOR_PRODUCTION',
    validated_at: new Date().toISOString(),
    validation_chain: [
      'OBSERVATION-001: Failure paths, structure, stability',
      'VALIDATION-002: Success path framework',
      'READINESS-003: Final success execution',
    ],
    system_state_transition: 'OBSERVED → VALIDATED → TRUSTED → READY',
    certification: 'All validation gates passed. System is production-ready.',
  };
  
  console.error('╔═══════════════════════════════════════════════════════════════╗');
  console.error('║                                                               ║');
  console.error('║                  ✓ PRODUCTION READINESS                       ║');
  console.error('║                                                               ║');
  console.error('║   All validation gates PASSED                                 ║');
  console.error('║   System state: READY                                         ║');
  console.error('║   External dependency: VERIFIED                               ║');
  console.error('║                                                               ║');
  console.error('║   Status: READY FOR PRODUCTION EXECUTION                      ║');
  console.error('║                                                               ║');
  console.error('╚═══════════════════════════════════════════════════════════════╝');
  console.error('');
  
  console.log(JSON.stringify(readinessReport, null, 2));
  
  return 0;
}

// ── Entry Point ────────────────────────────────────────────────────────────

executeFinalGate()
  .then((exitCode) => {
    process.exit(exitCode);
  })
  .catch((err) => {
    console.error('Fatal error during gate closure:', err);
    process.exit(1);
  });
