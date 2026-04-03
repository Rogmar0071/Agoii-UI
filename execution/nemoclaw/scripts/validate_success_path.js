#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * AGOII–EXECUTION-OBSERVATION-VALIDATION-002 — Success Path Validation
 * 
 * Focused validation script for the success path only.
 * Requires OPENAI_API_KEY to be set in environment.
 * 
 * This completes the final missing validation from OBSERVATION-001.
 */

'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

// ── Configuration ──────────────────────────────────────────────────────────

const REPO_ROOT = path.resolve(__dirname, '..');
const EXECUTE_JS = path.join(REPO_ROOT, 'execute.js');
const TMP_DIR = '/tmp/agoii-validation-002';

// Ensure tmp directory exists
if (!fs.existsSync(TMP_DIR)) {
  fs.mkdirSync(TMP_DIR, { recursive: true });
}

// ── Validation Report ──────────────────────────────────────────────────────

const validation = {
  validation_id: 'AGOII–EXECUTION-OBSERVATION-VALIDATION-002',
  timestamp: new Date().toISOString(),
  api_key_present: !!process.env.OPENAI_API_KEY,
  success_tests: [],
  metrics: {
    total_attempts: 0,
    successes: 0,
    failures: 0,
  },
  status: 'PENDING',
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
        parseError = `${err.message}`;
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

// ── Success Path Tests ─────────────────────────────────────────────────────

async function runSuccessPathValidation() {
  console.error('\n=== AGOII–EXECUTION-OBSERVATION-VALIDATION-002 ===\n');
  console.error('Success Path Validation — Final Stability Gate\n');
  
  // Pre-flight check
  if (!process.env.OPENAI_API_KEY) {
    log('PREFLIGHT', '✗ OPENAI_API_KEY not set');
    log('PREFLIGHT', 'This validation requires a valid OpenAI API key');
    log('PREFLIGHT', 'Set the key with: export OPENAI_API_KEY="sk-..."');
    console.error('');
    
    validation.status = 'BLOCKED';
    validation.reason = 'OPENAI_API_KEY environment variable not set';
    
    console.log(JSON.stringify(validation, null, 2));
    process.exit(1);
  }
  
  log('PREFLIGHT', '✓ OPENAI_API_KEY is set');
  console.error('');
  
  // Test 1: Basic success execution
  log('TEST-1', 'Basic success execution...');
  
  const test1Contract = {
    execution_id: 'validation-002-success-basic',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Say "validation successful" and nothing else.',
    },
    execution_policy: {
      process: {
        timeoutMs: 10000,
      },
    },
  };
  
  const test1 = await executeContract(test1Contract, 'Basic success');
  validation.metrics.total_attempts++;
  
  if (test1.report && test1.report.status === 'success') {
    validation.metrics.successes++;
    log('TEST-1', `✓ SUCCESS (${test1.durationMs}ms)`);
    log('TEST-1', `  Output: ${test1.report.outputs.length} item(s)`);
    
    validation.success_tests.push({
      test: 'basic_success',
      status: 'PASS',
      execution_id: test1.report.execution_id,
      duration_ms: test1.durationMs,
      output_count: test1.report.outputs.length,
    });
  } else {
    validation.metrics.failures++;
    log('TEST-1', `✗ FAILED`);
    if (test1.parseError) {
      log('TEST-1', `  Parse error: ${test1.parseError}`);
    } else if (test1.report) {
      log('TEST-1', `  Status: ${test1.report.status}`);
      if (test1.report.failure_surface) {
        log('TEST-1', `  Failure: ${test1.report.failure_surface.details}`);
      }
    }
    
    validation.success_tests.push({
      test: 'basic_success',
      status: 'FAIL',
      reason: test1.parseError || (test1.report ? test1.report.status : 'unknown'),
    });
  }
  
  console.error('');
  
  // Test 2: Short prompt
  log('TEST-2', 'Short prompt execution...');
  
  const test2Contract = {
    execution_id: 'validation-002-success-short',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Hi',
    },
    execution_policy: {
      process: {
        timeoutMs: 10000,
      },
    },
  };
  
  const test2 = await executeContract(test2Contract, 'Short prompt');
  validation.metrics.total_attempts++;
  
  if (test2.report && test2.report.status === 'success') {
    validation.metrics.successes++;
    log('TEST-2', `✓ SUCCESS (${test2.durationMs}ms)`);
    
    validation.success_tests.push({
      test: 'short_prompt',
      status: 'PASS',
      execution_id: test2.report.execution_id,
      duration_ms: test2.durationMs,
    });
  } else {
    validation.metrics.failures++;
    log('TEST-2', `✗ FAILED`);
    
    validation.success_tests.push({
      test: 'short_prompt',
      status: 'FAIL',
      reason: test2.parseError || (test2.report ? test2.report.status : 'unknown'),
    });
  }
  
  console.error('');
  
  // Test 3: Longer timeout
  log('TEST-3', 'Longer timeout execution...');
  
  const test3Contract = {
    execution_id: 'validation-002-success-timeout',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Respond with the word "complete".',
    },
    execution_policy: {
      process: {
        timeoutMs: 30000,
      },
    },
  };
  
  const test3 = await executeContract(test3Contract, 'Longer timeout');
  validation.metrics.total_attempts++;
  
  if (test3.report && test3.report.status === 'success') {
    validation.metrics.successes++;
    log('TEST-3', `✓ SUCCESS (${test3.durationMs}ms)`);
    
    validation.success_tests.push({
      test: 'longer_timeout',
      status: 'PASS',
      execution_id: test3.report.execution_id,
      duration_ms: test3.durationMs,
    });
  } else {
    validation.metrics.failures++;
    log('TEST-3', `✗ FAILED`);
    
    validation.success_tests.push({
      test: 'longer_timeout',
      status: 'FAIL',
      reason: test3.parseError || (test3.report ? test3.report.status : 'unknown'),
    });
  }
  
  console.error('');
  
  // Compute final status
  if (validation.metrics.successes === validation.metrics.total_attempts) {
    validation.status = 'PASS';
    log('VALIDATION', '✓ SUCCESS PATH VALIDATED');
  } else if (validation.metrics.successes > 0) {
    validation.status = 'PARTIAL';
    log('VALIDATION', `⚠ PARTIAL SUCCESS (${validation.metrics.successes}/${validation.metrics.total_attempts})`);
  } else {
    validation.status = 'FAIL';
    log('VALIDATION', '✗ SUCCESS PATH VALIDATION FAILED');
  }
  
  // Clean up temp directory
  try {
    const tmpFiles = fs.readdirSync(TMP_DIR).filter(f => f.startsWith('contract-'));
    if (tmpFiles.length > 0) {
      log('CLEANUP', `⚠ ${tmpFiles.length} orphan temp files detected`);
    } else {
      log('CLEANUP', '✓ All temp files cleaned up');
    }
  } catch (err) {
    // Ignore
  }
  
  console.error('');
  
  // Output final report
  console.log(JSON.stringify(validation, null, 2));
  
  return validation.status === 'PASS' ? 0 : 1;
}

// ── Entry Point ────────────────────────────────────────────────────────────

runSuccessPathValidation()
  .then((exitCode) => {
    process.exit(exitCode);
  })
  .catch((err) => {
    console.error('Fatal error during validation:', err);
    process.exit(1);
  });
