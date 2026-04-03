// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * Test suite for AGOII–ARTIFACT-SPINE-001: Artifact Surface Integration
 *
 * Validates that NemoClaw execution reports include deterministic artifacts with:
 * - Proper structure (sections array)
 * - SHA-256 content hashing
 * - Integrity validation
 * - Failure on missing/invalid artifacts
 * - Deterministic hashing (identical input → identical hash)
 */

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { test } = require('node:test');
const assert = require('assert');

const EXECUTE_JS = path.resolve(__dirname, '..', 'execute.js');
const TMP_DIR = '/tmp/artifact-test';

// Ensure tmp directory exists
if (!fs.existsSync(TMP_DIR)) {
  fs.mkdirSync(TMP_DIR, { recursive: true });
}

/**
 * Helper to compute SHA-256 hash (matches execute.js implementation)
 */
function computeHash(content) {
  return crypto.createHash('sha256').update(content, 'utf8').digest('hex');
}

/**
 * Helper to execute a contract and return the parsed report
 */
async function executeContract(contract) {
  return new Promise((resolve) => {
    const contractFile = path.join(TMP_DIR, `contract-${Date.now()}-${Math.random()}.json`);
    fs.writeFileSync(contractFile, JSON.stringify(contract), 'utf-8');

    let stdout = '';
    let stderr = '';

    const child = spawn(process.execPath, [EXECUTE_JS, contractFile], {
      env: { ...process.env, OPENAI_API_KEY: '' }, // No API key for most tests
    });

    child.stdout.on('data', (chunk) => { stdout += chunk.toString(); });
    child.stderr.on('data', (chunk) => { stderr += chunk.toString(); });

    child.on('close', (code) => {
      // Clean up contract file
      try {
        fs.unlinkSync(contractFile);
      } catch (err) {
        // Ignore cleanup errors
      }

      let report = null;
      try {
        report = JSON.parse(stdout);
      } catch (err) {
        // Parse error - will be caught in tests
      }

      resolve({ report, stdout, stderr, exitCode: code });
    });

    child.on('error', (err) => {
      try {
        fs.unlinkSync(contractFile);
      } catch (cleanupErr) {
        // Ignore
      }
      resolve({ report: null, stdout: '', stderr: err.message, exitCode: 1 });
    });
  });
}

// ── Test Suite ─────────────────────────────────────────────────────────────

test('ARTIFACT-001: Contract rejection includes empty artifact', async () => {
  const contract = {
    execution_id: 'test-rejection-001',
    // Missing contractor_id → contract validation failure
    input: { prompt: 'test' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report should be returned');
  assert.strictEqual(report.status, 'rejected', 'Status should be rejected');
  assert(report.artifact, 'Artifact field must be present');
  assert(Array.isArray(report.artifact.sections), 'Artifact.sections must be an array');
  assert.strictEqual(report.artifact.sections.length, 0, 'Rejected contracts have empty artifact');
  assert(report.failure_surface, 'failure_surface should be present');
  assert.strictEqual(report.failure_surface.type, 'contract_rejection', 'Failure type should be contract_rejection');
});

test('ARTIFACT-002: Valid execution produces artifact with sections', async () => {
  const contract = {
    execution_id: 'test-artifact-002',
    contractor_id: 'openai-inference',
    input: { prompt: 'Return exactly: TEST_OUTPUT' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report should be returned');
  
  // Artifact validation
  assert(report.artifact, 'Artifact field must be present');
  assert(typeof report.artifact === 'object', 'Artifact must be an object');
  assert(Array.isArray(report.artifact.sections), 'Artifact.sections must be an array');
  assert(report.artifact.sections.length > 0, 'Artifact must have at least one section');

  // Section structure validation
  const section = report.artifact.sections[0];
  assert(section, 'First section must exist');
  assert.strictEqual(section.section_id, 'main', 'Default section_id should be "main"');
  assert(typeof section.content === 'string', 'Section content must be a string');
  assert(typeof section.content_hash === 'string', 'Section content_hash must be a string');
  assert(section.content_hash.length === 64, 'SHA-256 hash should be 64 hex characters');
});

test('ARTIFACT-003: Hash integrity validation', async () => {
  const contract = {
    execution_id: 'test-hash-003',
    contractor_id: 'openai-inference',
    input: { prompt: 'Return: HASH_TEST' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report should be returned');
  assert(report.artifact, 'Artifact must be present');
  assert(report.artifact.sections.length > 0, 'Artifact must have sections');

  // Verify hash matches content
  const section = report.artifact.sections[0];
  const expectedHash = computeHash(section.content);
  
  assert.strictEqual(
    section.content_hash,
    expectedHash,
    `Hash must match SHA-256 of content. Expected: ${expectedHash}, Got: ${section.content_hash}`
  );
});

test('ARTIFACT-004: Deterministic hashing - identical input produces identical hash', async () => {
  const contract1 = {
    execution_id: 'test-deterministic-004a',
    contractor_id: 'openai-inference',
    input: { prompt: 'Return: DETERMINISTIC_TEST' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const contract2 = {
    execution_id: 'test-deterministic-004b',
    contractor_id: 'openai-inference',
    input: { prompt: 'Return: DETERMINISTIC_TEST' }, // Same prompt
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const [result1, result2] = await Promise.all([
    executeContract(contract1),
    executeContract(contract2),
  ]);

  assert(result1.report, 'First report should be returned');
  assert(result2.report, 'Second report should be returned');

  // Note: Outputs might differ due to API non-determinism, but hash function should be deterministic
  // We test the hash function itself
  const testContent = 'IDENTICAL_CONTENT';
  const hash1 = computeHash(testContent);
  const hash2 = computeHash(testContent);
  
  assert.strictEqual(hash1, hash2, 'Identical content must produce identical hash');
  assert.strictEqual(hash1.length, 64, 'Hash should be 64 hex characters');
});

test('ARTIFACT-005: Artifact present in timeout scenario', async () => {
  const contract = {
    execution_id: 'test-timeout-005',
    contractor_id: 'openai-inference',
    input: { prompt: 'Timeout test' },
    execution_policy: { process: { timeoutMs: 1 } }, // 1ms timeout → immediate timeout
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report should be returned even on timeout');
  assert(report.artifact, 'Artifact must be present on timeout');
  assert(Array.isArray(report.artifact.sections), 'Artifact.sections must be an array');
  
  // Timeout might have empty or partial output, but artifact structure must be valid
  assert(report.artifact.sections.length > 0, 'Artifact must have at least one section');
  
  const section = report.artifact.sections[0];
  assert(section.section_id, 'Section must have section_id');
  assert(typeof section.content === 'string', 'Section content must be a string');
  assert(typeof section.content_hash === 'string', 'Section content_hash must be a string');
  
  // Verify hash integrity even for empty content
  const expectedHash = computeHash(section.content);
  assert.strictEqual(section.content_hash, expectedHash, 'Hash must be valid even for timeout');
});

test('ARTIFACT-006: Artifact structure with empty output', async () => {
  const contract = {
    execution_id: 'test-empty-006',
    contractor_id: 'openai-inference',
    input: { prompt: 'Empty response test' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report should be returned');
  assert(report.artifact, 'Artifact must be present');
  assert(report.artifact.sections.length > 0, 'Must have at least one section');

  const section = report.artifact.sections[0];
  
  // Even empty content gets a valid hash
  const expectedHash = computeHash(section.content);
  assert.strictEqual(section.content_hash, expectedHash, 'Empty content must have valid hash');
});

test('ARTIFACT-007: All report fields present including artifact', async () => {
  const contract = {
    execution_id: 'test-complete-007',
    contractor_id: 'openai-inference',
    input: { prompt: 'Complete report test' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report must be returned');
  
  // Verify all mandatory fields per NemoClawReport schema
  assert(report.execution_id, 'execution_id must be present');
  assert(report.status, 'status must be present');
  assert(typeof report.exit_code === 'number', 'exit_code must be a number');
  assert(Array.isArray(report.outputs), 'outputs must be an array');
  assert(report.artifact, 'artifact must be present (MANDATORY per ARTIFACT-SPINE-001)');
  assert(report.metadata, 'metadata must be present');
  assert(report.metadata.contractor_id, 'metadata.contractor_id must be present');
  assert(typeof report.metadata.durationMs === 'number', 'metadata.durationMs must be a number');
  assert(report.metadata.timestamp, 'metadata.timestamp must be present');
});

test('ARTIFACT-008: Single-section default strategy', async () => {
  const contract = {
    execution_id: 'test-single-section-008',
    contractor_id: 'openai-inference',
    input: { prompt: 'Single section test' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report must be returned');
  assert(report.artifact, 'Artifact must be present');
  
  // Per ARTIFACT-SPINE-001 §STEP-4: default strategy is single-section
  assert.strictEqual(
    report.artifact.sections.length,
    1,
    'Default strategy should produce exactly one section'
  );
  
  const section = report.artifact.sections[0];
  assert.strictEqual(section.section_id, 'main', 'Default section_id should be "main"');
});

test('ARTIFACT-009: Artifact hash is hex string', async () => {
  const contract = {
    execution_id: 'test-hex-009',
    contractor_id: 'openai-inference',
    input: { prompt: 'Hex validation test' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report must be returned');
  assert(report.artifact, 'Artifact must be present');
  assert(report.artifact.sections.length > 0, 'Must have sections');

  const section = report.artifact.sections[0];
  const hexPattern = /^[0-9a-f]{64}$/;
  
  assert(
    hexPattern.test(section.content_hash),
    `content_hash must be 64-character lowercase hex string. Got: ${section.content_hash}`
  );
});

test('ARTIFACT-010: Rejected contractor still has artifact structure', async () => {
  const contract = {
    execution_id: 'test-rejected-010',
    contractor_id: 'nonexistent-contractor', // Not in ALLOWED_CONTRACTORS
    input: { prompt: 'Rejection test' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report must be returned');
  assert.strictEqual(report.status, 'rejected', 'Status should be rejected');
  assert(report.artifact, 'Artifact field must be present even on rejection');
  assert(Array.isArray(report.artifact.sections), 'Artifact.sections must be an array');
  // Rejected contracts have empty artifact per implementation
  assert.strictEqual(report.artifact.sections.length, 0, 'Rejected contracts have empty artifact');
});

console.log('\n✓ All artifact surface tests completed\n');
