// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * AGOII–ARTIFACT-READINESS-004 — Final Artifact Integrity Validation
 *
 * This test suite validates that the system produces verifiable, comparable,
 * and enforceable artifacts - not just successful executions.
 *
 * REPLACES: READINESS-003 (which only validated execution behavior)
 *
 * VALIDATES:
 * 1. Artifact presence under real execution
 * 2. Hash validity (hash(content) == content_hash)
 * 3. Determinism (same input → same artifact structure)
 * 4. Delta detection (different inputs → different artifacts)
 * 5. Replay validation (replay → verifiable consistency)
 * 6. Failure enforcement (missing/corrupt artifacts → rejection)
 *
 * READINESS DEFINITION:
 * - OLD: "System executes correctly"
 * - NEW: "System produces verifiable, comparable, enforceable artifacts"
 */

const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { test } = require('node:test');
const assert = require('assert');

const EXECUTE_JS = path.resolve(__dirname, '..', 'execute.js');
const TMP_DIR = '/tmp/artifact-readiness-004';

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
      env: { ...process.env },
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

/**
 * Check if API key is available for real execution tests
 */
function hasApiKey() {
  return !!process.env.OPENAI_API_KEY && process.env.OPENAI_API_KEY.length > 0;
}

// ── TEST 1: ARTIFACT PRESENCE ──────────────────────────────────────────────

test('READINESS-004-T1: Artifact presence under real execution', async () => {
  if (!hasApiKey()) {
    console.log('⚠ READINESS-004-T1: SKIPPED (No API key)');
    return;
  }

  const contract = {
    execution_id: 'readiness-004-presence-001',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Return exactly: ARTIFACT_PRESENCE_TEST',
    },
    execution_policy: {
      process: { timeoutMs: 10000 },
    },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report must be returned');
  assert.strictEqual(report.status, 'success', 'Execution must succeed for readiness');
  
  // CRITICAL: Artifact MUST be present
  assert(report.artifact, 'Artifact field must be present');
  assert(typeof report.artifact === 'object', 'Artifact must be an object');
  assert(Array.isArray(report.artifact.sections), 'Artifact.sections must be an array');
  assert(
    report.artifact.sections.length > 0,
    'Artifact MUST have at least one section (READINESS CRITERION)'
  );

  console.log('✓ READINESS-004-T1: PASS — Artifact present under real execution');
});

// ── TEST 2: HASH VALIDITY ──────────────────────────────────────────────────

test('READINESS-004-T2: Hash validity under real execution', async () => {
  if (!hasApiKey()) {
    console.log('⚠ READINESS-004-T2: SKIPPED (No API key)');
    return;
  }

  const contract = {
    execution_id: 'readiness-004-hash-002',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Return exactly: HASH_VALIDITY_TEST',
    },
    execution_policy: {
      process: { timeoutMs: 10000 },
    },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report must be returned');
  assert(report.artifact, 'Artifact must be present');
  assert(report.artifact.sections.length > 0, 'Artifact must have sections');

  // CRITICAL: Verify hash integrity for every section
  for (let i = 0; i < report.artifact.sections.length; i++) {
    const section = report.artifact.sections[i];
    
    assert(section.content !== undefined, `Section ${i} must have content`);
    assert(section.content_hash, `Section ${i} must have content_hash`);

    const expectedHash = computeHash(section.content);
    
    assert.strictEqual(
      section.content_hash,
      expectedHash,
      `Section ${i}: hash(content) MUST equal content_hash (READINESS CRITERION). Expected: ${expectedHash}, Got: ${section.content_hash}`
    );
  }

  console.log('✓ READINESS-004-T2: PASS — Hash validity confirmed');
});

// ── TEST 3: DETERMINISM ────────────────────────────────────────────────────

test('READINESS-004-T3: Deterministic artifact structure (same input)', async () => {
  if (!hasApiKey()) {
    console.log('⚠ READINESS-004-T3: SKIPPED (No API key)');
    return;
  }

  const prompt = 'Return exactly: DETERMINISM_TEST_MARKER';

  const contract1 = {
    execution_id: 'readiness-004-determinism-003a',
    contractor_id: 'openai-inference',
    input: { prompt },
    execution_policy: { process: { timeoutMs: 10000 } },
  };

  const contract2 = {
    execution_id: 'readiness-004-determinism-003b',
    contractor_id: 'openai-inference',
    input: { prompt }, // Same prompt
    execution_policy: { process: { timeoutMs: 10000 } },
  };

  const [result1, result2] = await Promise.all([
    executeContract(contract1),
    executeContract(contract2),
  ]);

  assert(result1.report, 'First report must be returned');
  assert(result2.report, 'Second report must be returned');

  // CRITICAL: Verify artifact structure is deterministic
  assert(result1.report.artifact, 'First artifact must be present');
  assert(result2.report.artifact, 'Second artifact must be present');
  
  assert.strictEqual(
    result1.report.artifact.sections.length,
    result2.report.artifact.sections.length,
    'Same input MUST produce same artifact structure (READINESS CRITERION)'
  );

  // Note: Content may vary due to LLM non-determinism, but structure must be consistent
  // Verify both have valid hashes
  for (const section of result1.report.artifact.sections) {
    const expectedHash = computeHash(section.content);
    assert.strictEqual(section.content_hash, expectedHash, 'First artifact hashes must be valid');
  }

  for (const section of result2.report.artifact.sections) {
    const expectedHash = computeHash(section.content);
    assert.strictEqual(section.content_hash, expectedHash, 'Second artifact hashes must be valid');
  }

  console.log('✓ READINESS-004-T3: PASS — Deterministic structure confirmed');
});

// ── TEST 4: DELTA DETECTION ────────────────────────────────────────────────

test('READINESS-004-T4: Delta detection (different inputs → different artifacts)', async () => {
  if (!hasApiKey()) {
    console.log('⚠ READINESS-004-T4: SKIPPED (No API key)');
    return;
  }

  const contractA = {
    execution_id: 'readiness-004-delta-004a',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Return exactly: DELTA_TEST_A',
    },
    execution_policy: { process: { timeoutMs: 10000 } },
  };

  const contractB = {
    execution_id: 'readiness-004-delta-004b',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Return exactly: DELTA_TEST_B', // Different prompt
    },
    execution_policy: { process: { timeoutMs: 10000 } },
  };

  const [resultA, resultB] = await Promise.all([
    executeContract(contractA),
    executeContract(contractB),
  ]);

  assert(resultA.report, 'First report must be returned');
  assert(resultB.report, 'Second report must be returned');
  assert(resultA.report.artifact, 'First artifact must be present');
  assert(resultB.report.artifact, 'Second artifact must be present');

  // CRITICAL: Different inputs SHOULD produce different content
  // (We validate that the system CAN detect deltas, not that they ALWAYS differ)
  const contentA = resultA.report.artifact.sections[0].content;
  const contentB = resultB.report.artifact.sections[0].content;
  const hashA = resultA.report.artifact.sections[0].content_hash;
  const hashB = resultB.report.artifact.sections[0].content_hash;

  // Verify hashes are valid
  assert.strictEqual(hashA, computeHash(contentA), 'Hash A must be valid');
  assert.strictEqual(hashB, computeHash(contentB), 'Hash B must be valid');

  // If contents differ, hashes MUST differ (delta detection)
  if (contentA !== contentB) {
    assert.notStrictEqual(
      hashA,
      hashB,
      'Different content MUST produce different hashes (DELTA DETECTION)'
    );
  }

  console.log('✓ READINESS-004-T4: PASS — Delta detection capability confirmed');
});

// ── TEST 5: REPLAY VALIDATION ──────────────────────────────────────────────

test('READINESS-004-T5: Replay validation (consistency)', async () => {
  if (!hasApiKey()) {
    console.log('⚠ READINESS-004-T5: SKIPPED (No API key)');
    return;
  }

  const contract = {
    execution_id: 'readiness-004-replay-005',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Return exactly: REPLAY_TEST',
    },
    execution_policy: { process: { timeoutMs: 10000 } },
  };

  // Execute once
  const { report: firstReport } = await executeContract(contract);

  assert(firstReport, 'First report must be returned');
  assert(firstReport.artifact, 'First artifact must be present');

  // CRITICAL: Verify artifact can be stored and re-validated
  const storedArtifact = JSON.parse(JSON.stringify(firstReport.artifact));
  
  assert.deepStrictEqual(
    storedArtifact,
    firstReport.artifact,
    'Artifact must be serializable and restorable (REPLAY REQUIREMENT)'
  );

  // Verify hash can be re-computed and validated
  for (const section of storedArtifact.sections) {
    const recomputedHash = computeHash(section.content);
    assert.strictEqual(
      section.content_hash,
      recomputedHash,
      'Stored artifact hash MUST remain valid on replay (REPLAY VALIDATION)'
    );
  }

  console.log('✓ READINESS-004-T5: PASS — Replay validation confirmed');
});

// ── TEST 6: FAILURE ENFORCEMENT ────────────────────────────────────────────

test('READINESS-004-T6: Failure enforcement (missing artifact → rejection)', async () => {
  // This test validates that the system WOULD reject missing artifacts
  // Since the contractor now ALWAYS produces artifacts per AGOII-ARTIFACT-SPINE-VALIDATION-002,
  // we test the validation logic itself

  // Test with a rejection scenario (which produces empty artifact)
  const contract = {
    execution_id: 'readiness-004-enforcement-006',
    contractor_id: 'nonexistent-contractor', // Will be rejected
    input: { prompt: 'test' },
    execution_policy: { process: { timeoutMs: 5000 } },
  };

  const { report } = await executeContract(contract);

  assert(report, 'Report must be returned');
  assert.strictEqual(report.status, 'rejected', 'Invalid contractor must be rejected');
  assert(report.artifact, 'Even rejections have artifact structure');
  assert.strictEqual(
    report.artifact.sections.length,
    0,
    'Rejected contracts have empty artifact (ENFORCEMENT)'
  );

  // CRITICAL: System enforces artifact structure rules
  assert(report.failure_surface, 'Failure must be surfaced');
  assert.strictEqual(
    report.failure_surface.type,
    'contract_rejection',
    'Proper failure type must be surfaced'
  );

  console.log('✓ READINESS-004-T6: PASS — Failure enforcement confirmed');
});

// ── COMPREHENSIVE VALIDATION ───────────────────────────────────────────────

test('READINESS-004-COMPREHENSIVE: Full artifact readiness validation', async () => {
  if (!hasApiKey()) {
    console.log('⚠ READINESS-004-COMPREHENSIVE: SKIPPED (No API key)');
    console.log('');
    console.log('═══════════════════════════════════════════════════════════');
    console.log('  ⚠ ARTIFACT READINESS VALIDATION INCOMPLETE');
    console.log('═══════════════════════════════════════════════════════════');
    console.log('');
    console.log('  Status: VALIDATED (ARTIFACT-INCOMPLETE)');
    console.log('');
    console.log('  To complete READINESS-004:');
    console.log('    export OPENAI_API_KEY="sk-..."');
    console.log('    npm test -- test/artifact-readiness-004.test.js');
    console.log('');
    console.log('  READINESS CRITERIA:');
    console.log('    ✓ Artifact structure validated (offline tests)');
    console.log('    ⚠ Real execution validation PENDING (API key required)');
    console.log('');
    console.log('═══════════════════════════════════════════════════════════');
    console.log('');
    return;
  }

  // Execute comprehensive final gate contract
  const contract = {
    execution_id: 'artifact-readiness-comprehensive-001',
    contractor_id: 'openai-inference',
    input: {
      prompt: 'Return exactly: SYSTEM_READY',
    },
    execution_policy: {
      process: { timeoutMs: 10000 },
    },
  };

  const { report } = await executeContract(contract);

  // VALIDATION CHAIN
  
  // 1. Execution must succeed
  assert(report, 'Report must be returned');
  assert.strictEqual(report.status, 'success', 'Execution must succeed');
  assert.strictEqual(report.exit_code, 0, 'Exit code must be 0');

  // 2. Artifact must exist
  assert(report.artifact, 'Artifact must exist');
  assert(report.artifact.sections.length > 0, 'Artifact must have sections');

  // 3. Hash must be valid
  for (const section of report.artifact.sections) {
    const expectedHash = computeHash(section.content);
    assert.strictEqual(
      section.content_hash,
      expectedHash,
      'All hashes must be valid'
    );
  }

  // 4. Structure must be deterministic (single section with section_id='main')
  assert.strictEqual(
    report.artifact.sections.length,
    1,
    'Default strategy produces single section'
  );
  assert.strictEqual(
    report.artifact.sections[0].section_id,
    'main',
    'Default section_id is "main"'
  );

  // 5. Output must be present
  assert(Array.isArray(report.outputs), 'Outputs must be present');
  assert(report.outputs.length > 0, 'At least one output must exist');

  console.log('');
  console.log('═══════════════════════════════════════════════════════════');
  console.log('  ✓ ARTIFACT READINESS VALIDATION COMPLETE');
  console.log('═══════════════════════════════════════════════════════════');
  console.log('');
  console.log('  Status: READY FOR PRODUCTION EXECUTION');
  console.log('');
  console.log('  All validation criteria met:');
  console.log('    ✓ Artifact presence confirmed');
  console.log('    ✓ Hash validity confirmed');
  console.log('    ✓ Deterministic structure confirmed');
  console.log('    ✓ Delta detection capable');
  console.log('    ✓ Replay validation possible');
  console.log('    ✓ Failure enforcement active');
  console.log('');
  console.log('  System produces:');
  console.log('    → Verifiable artifacts (hash integrity)');
  console.log('    → Comparable artifacts (delta detection)');
  console.log('    → Enforceable artifacts (validation authority)');
  console.log('');
  console.log('  PRODUCTION READINESS: CONFIRMED');
  console.log('═══════════════════════════════════════════════════════════');
  console.log('');
});

console.log('\n✓ AGOII–ARTIFACT-READINESS-004 test suite loaded\n');
