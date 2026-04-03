#!/usr/bin/env node
// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * execute.js — NemoClaw execution contractor (AGOII–NEMOCLAW-INTEGRATION-001)
 *
 * Usage:
 *   node execute.js <contract-file.json>
 *
 * Accepts an Agoii execution contract JSON file, validates it, resolves the
 * declared contractor_id against the registry and allowed set, executes via
 * the appropriate adapter, and prints a single serialised ExecutionReport to
 * stdout.  All diagnostic output is routed to stderr.  The process exits
 * deterministically after execution completes.
 *
 * Contract shape (AGOII–NEMOCLAW-INTEGRATION-001 §STEP-1):
 *   {
 *     "execution_id":    string,
 *     "contractor_id":   string,
 *     "input":           { "prompt": string },
 *     "execution_policy": { "process": { "timeoutMs": number } }
 *   }
 *
 * This file is self-contained: all validation, registry, adapter, and report
 * logic is inlined as plain JavaScript.  No build step, TypeScript compilation,
 * or external dependencies are required.
 */

'use strict';

const crypto = require('crypto');
const fs = require('fs');
const https = require('https');
const path = require('path');

// ── Contract Validation ───────────────────────────────────────────────────

class ContractValidationError extends Error {
  constructor(field, message) {
    super(`ContractValidationError [${field}]: ${message}`);
    this.name = 'ContractValidationError';
    this.field = field;
  }
}

function validateContract(contract) {
  if (typeof contract !== 'object' || contract === null) {
    throw new ContractValidationError('contract', 'must be a non-null object');
  }

  if (typeof contract.execution_id !== 'string' || contract.execution_id.trim() === '') {
    throw new ContractValidationError('execution_id', 'must be a non-empty string');
  }

  if (typeof contract.contractor_id !== 'string' || contract.contractor_id.trim() === '') {
    throw new ContractValidationError('contractor_id', 'must be a non-empty string');
  }

  if (typeof contract.input !== 'object' || contract.input === null) {
    throw new ContractValidationError('input', 'must be a non-null object');
  }

  if (typeof contract.input.prompt !== 'string' || contract.input.prompt.trim() === '') {
    throw new ContractValidationError('input.prompt', 'must be a non-empty string');
  }

  if (typeof contract.execution_policy !== 'object' || contract.execution_policy === null) {
    throw new ContractValidationError('execution_policy', 'must be a non-null object');
  }

  if (
    typeof contract.execution_policy.process !== 'object' ||
    contract.execution_policy.process === null
  ) {
    throw new ContractValidationError('execution_policy.process', 'must be a non-null object');
  }

  if (typeof contract.execution_policy.process.timeoutMs !== 'number') {
    throw new ContractValidationError('execution_policy.process.timeoutMs', 'must be a number');
  }

  return contract;
}

// ── Contractor Registry ───────────────────────────────────────────────────

// Each entry declares:
//   contractor_id  — unique identifier for this contractor (used for routing)
//   adapter        — 'openai'
//
// The registry is the full set of known contractors.  Presence here is
// necessary but not sufficient for execution — a contractor must also appear
// in ALLOWED_CONTRACTORS to be permitted to run.
const CONTRACTOR_REGISTRY = [
  {
    contractor_id: 'openai-inference',
    adapter: 'openai',
  },
];

// Allowed contractor IDs — execution is restricted exclusively to this set.
//
// This is intentionally maintained separately from CONTRACTOR_REGISTRY so that
// a contractor can be registered (making it discoverable) without being
// allowed for execution.  Any contractor absent from this set is rejected at
// execution time regardless of registry presence, keeping execution paths
// deterministic and preventing unapproved code paths from running.
//
// Per AGOII–NEMOCLAW-INTEGRATION-001: only openai-inference is permitted.
//
// To add a contractor: it must first appear in CONTRACTOR_REGISTRY AND be
// explicitly listed here after formal approval.  Do not add entries without
// a corresponding integration contract authorising the new execution path.
const ALLOWED_CONTRACTORS = new Set([
  'openai-inference',
]);

function resolveContractor(contractorId) {
  return CONTRACTOR_REGISTRY.find((c) => c.contractor_id === contractorId) || null;
}

// ── OpenAI Adapter ────────────────────────────────────────────────────────

function openaiRequest(requestPayload, timeoutMs) {
  return new Promise((resolve) => {
    const apiKey = process.env.OPENAI_API_KEY || '';
    if (!apiKey) {
      resolve({ rawOutput: '', exitCode: 1, error: 'OPENAI_API_KEY not set' });
      return;
    }

    const body = JSON.stringify(requestPayload);
    const options = {
      hostname: 'api.openai.com',
      path: '/v1/chat/completions',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${apiKey}`,
        'Content-Length': Buffer.byteLength(body),
      },
    };

    let timer = null;
    let settled = false;

    const req = https.request(options, (res) => {
      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        resolve({ rawOutput: data, exitCode: res.statusCode >= 200 && res.statusCode < 300 ? 0 : 1 });
      });
    });

    req.on('error', (err) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      resolve({ rawOutput: err.message, exitCode: 1 });
    });

    if (timeoutMs > 0) {
      timer = setTimeout(() => {
        if (settled) return;
        settled = true;
        req.destroy();
        resolve({ rawOutput: '', exitCode: 124, timedOut: true });
      }, timeoutMs);
    }

    req.write(body);
    req.end();
  });
}

/**
 * Computes SHA-256 hash of content for artifact integrity verification.
 * Per AGOII–ARTIFACT-SPINE-VALIDATION-002: artifact generation is contractor responsibility.
 */
function computeContentHash(content) {
  return crypto.createHash('sha256').update(content, 'utf8').digest('hex');
}

/**
 * Normalizes OpenAI response to canonical result format.
 * Per AGOII–ARTIFACT-NORMALIZATION-001: Extract only deterministic result content.
 * 
 * Transforms raw OpenAI response JSON (which contains non-deterministic fields 
 * like id, created, system_fingerprint) into normalized canonical output:
 * { "result": "<actual-message-content>" }
 * 
 * This ensures artifact hashing is deterministic and replay validation succeeds.
 *
 * @param {string} rawOutput - The raw OpenAI API response JSON string
 * @returns {string} Normalized canonical output as JSON string
 */
function normalizeOpenAIResponse(rawOutput) {
  if (!rawOutput || rawOutput.trim() === '') {
    return JSON.stringify({ result: '' });
  }

  try {
    const response = JSON.parse(rawOutput);
    
    // Extract canonical result from OpenAI response structure
    // OpenAI response: { choices: [{ message: { content: "..." } }], ... }
    if (response.choices && 
        Array.isArray(response.choices) && 
        response.choices.length > 0 &&
        response.choices[0].message &&
        typeof response.choices[0].message.content === 'string') {
      
      const result = response.choices[0].message.content;
      return JSON.stringify({ result });
    }
    
    // If response doesn't match expected structure, return empty result
    return JSON.stringify({ result: '' });
  } catch (err) {
    // If JSON parsing fails, treat as plain text result
    return JSON.stringify({ result: rawOutput });
  }
}

/**
 * Builds a deterministic artifact from execution output.
 * Per AGOII–ARTIFACT-SPINE-VALIDATION-002: contractor MUST produce artifact.
 * Per AGOII–ARTIFACT-NORMALIZATION-001: Use normalized canonical output.
 *
 * @param {string} rawOutput - The raw output from execution
 * @param {string} adapter - The adapter type ('openai', etc.) for normalization
 * @returns {object} ExecutionArtifact with sections array
 */
function buildArtifact(rawOutput, adapter) {
  // Per AGOII–ARTIFACT-NORMALIZATION-001: Transform to canonical format
  let normalizedContent;
  if (adapter === 'openai') {
    normalizedContent = normalizeOpenAIResponse(rawOutput);
  } else {
    // Default strategy for non-OpenAI adapters: use raw output
    normalizedContent = rawOutput || '';
  }
  
  const contentHash = computeContentHash(normalizedContent);

  return {
    sections: [
      {
        section_id: 'main',
        content: normalizedContent,
        content_hash: contentHash,
      },
    ],
  };
}

async function executeOpenAIAdapter(contract) {
  const timeoutMs = contract.execution_policy.process.timeoutMs;

  const requestPayload = {
    model: 'gpt-4o',
    messages: [{ role: 'user', content: contract.input.prompt }],
  };

  const result = await openaiRequest(requestPayload, timeoutMs);
  
  // Per AGOII–ARTIFACT-SPINE-VALIDATION-002 §STEP-1:
  // Contractor MUST produce artifact as part of its output
  // Per AGOII–ARTIFACT-NORMALIZATION-001: Use normalized canonical output
  const artifact = buildArtifact(result.rawOutput || '', 'openai');
  
  return {
    ...result,
    artifact,
  };
}

// ── Artifact Validator ────────────────────────────────────────────────────

/**
 * Computes SHA-256 hash of content for artifact integrity verification.
 * Used by validator to verify contractor-produced hashes.
 * Per AGOII–ARTIFACT-SPINE-VALIDATION-002: validation authority uses this to CHECK hashes.
 */
function computeContentHash(content) {
  return crypto.createHash('sha256').update(content, 'utf8').digest('hex');
}

/**
 * Validates an artifact structure.
 * Per AGOII–ARTIFACT-SPINE-VALIDATION-002 §STEP-2: execute.js ONLY validates, does not create.
 *
 * @param {object} artifact - The artifact to validate
 * @returns {object} { valid: boolean, error?: string }
 */
function validateArtifact(artifact) {
  if (!artifact) {
    return { valid: false, error: 'Artifact is missing' };
  }

  if (typeof artifact !== 'object' || artifact === null) {
    return { valid: false, error: 'Artifact must be a non-null object' };
  }

  if (!Array.isArray(artifact.sections)) {
    return { valid: false, error: 'Artifact.sections must be an array' };
  }

  if (artifact.sections.length === 0) {
    return { valid: false, error: 'Artifact.sections must contain at least one section' };
  }

  for (let i = 0; i < artifact.sections.length; i++) {
    const section = artifact.sections[i];

    if (typeof section !== 'object' || section === null) {
      return { valid: false, error: `Section ${i} must be a non-null object` };
    }

    if (typeof section.section_id !== 'string' || section.section_id.trim() === '') {
      return { valid: false, error: `Section ${i} section_id must be a non-empty string` };
    }

    if (typeof section.content !== 'string') {
      return { valid: false, error: `Section ${i} content must be a string` };
    }

    if (typeof section.content_hash !== 'string' || section.content_hash.trim() === '') {
      return { valid: false, error: `Section ${i} content_hash must be a non-empty string` };
    }

    // Verify hash integrity
    const expectedHash = computeContentHash(section.content);
    if (section.content_hash !== expectedHash) {
      return { 
        valid: false, 
        error: `Section ${i} hash mismatch: expected ${expectedHash}, got ${section.content_hash}` 
      };
    }
  }

  return { valid: true };
}

// ── Execution Report Builder ──────────────────────────────────────────────

function buildFailureSurface(status, exitCode) {
  if (status === 'timeout') {
    return { type: 'timeout', source: 'process', details: 'Execution exceeded allowed time' };
  }
  if (status === 'failure') {
    return { type: 'execution_error', source: 'sandbox', details: `Non-zero exit code: ${exitCode}` };
  }
  return undefined;
}

function buildExecutionReport({ contract, status, exitCode, rawOutput, artifact, startMs }) {
  const now = new Date();
  const outputs = [];
  if (rawOutput && rawOutput.trim()) {
    outputs.push({ contentType: 'text/plain', content: rawOutput.trim() });
  }

  // Per AGOII–ARTIFACT-SPINE-VALIDATION-002 §STEP-4:
  // Artifact MUST come from contractor. Hard fail if missing.
  if (!artifact) {
    return {
      execution_id: contract.execution_id,
      status: 'failure',
      exit_code: 1,
      outputs: [],
      artifact: { sections: [] }, // Empty artifact to maintain schema compliance
      metadata: {
        contractor_id: contract.contractor_id,
        durationMs: now.getTime() - startMs,
        timestamp: now.toISOString(),
      },
      failure_surface: {
        type: 'missing_artifact',
        source: 'artifact',
        details: 'Contractor did not produce required artifact',
      },
    };
  }

  // Validate contractor-provided artifact (AGOII–ARTIFACT-SPINE-VALIDATION-002 §STEP-7)
  const validation = validateArtifact(artifact);
  if (!validation.valid) {
    // Artifact validation failure → execution fails
    // Per AGOII–ARTIFACT-SPINE-VALIDATION-002 §STEP-10: execution MUST FAIL if artifact invalid
    return {
      execution_id: contract.execution_id,
      status: 'failure',
      exit_code: 1,
      outputs: [],
      artifact: { sections: [] }, // Empty artifact to maintain schema compliance
      metadata: {
        contractor_id: contract.contractor_id,
        durationMs: now.getTime() - startMs,
        timestamp: now.toISOString(),
      },
      failure_surface: {
        type: 'invalid_artifact',
        source: 'artifact',
        details: validation.error || 'Artifact validation failed',
      },
    };
  }

  const report = {
    execution_id: contract.execution_id,
    status,
    exit_code: exitCode,
    outputs,
    artifact, // Use contractor-provided artifact (AGOII–ARTIFACT-SPINE-VALIDATION-002 §STEP-2)
    metadata: {
      contractor_id: contract.contractor_id,
      durationMs: now.getTime() - startMs,
      timestamp: now.toISOString(),
    },
  };

  const failure = buildFailureSurface(status, exitCode);
  if (failure) {
    report.failure_surface = failure;
  }

  return report;
}

function buildRejectionReport({ execution_id, reason, startMs }) {
  const now = new Date();
  return {
    execution_id: execution_id || 'unknown',
    status: 'rejected',
    exit_code: 1,
    outputs: [],
    artifact: { sections: [] }, // Empty artifact for rejected contracts
    metadata: {
      contractor_id: 'unknown',
      durationMs: now.getTime() - startMs,
      timestamp: now.toISOString(),
    },
    failure_surface: {
      type: 'contract_rejection',
      source: 'validator',
      details: reason,
    },
  };
}

// ── Main Execution Orchestrator ───────────────────────────────────────────

async function executeContract(contract) {
  const startMs = Date.now();

  // Step 1: Validate contract structure
  let validated;
  try {
    validated = validateContract(contract);
  } catch (err) {
    const reason =
      err instanceof ContractValidationError
        ? err.message
        : `Contract validation failed: ${String(err)}`;
    const c = typeof contract === 'object' && contract !== null ? contract : {};
    return buildRejectionReport({
      execution_id: typeof c.execution_id === 'string' ? c.execution_id : undefined,
      reason,
      startMs,
    });
  }

  // Step 2: Resolve contractor from registry by contractor_id (no implicit fallback)
  const contractor = resolveContractor(validated.contractor_id);
  if (!contractor) {
    return buildRejectionReport({
      execution_id: validated.execution_id,
      reason: `No registered contractor for contractor_id: ${validated.contractor_id}`,
      startMs,
    });
  }

  // Step 2b: Enforce allowed-contractor gate — reject contractors not present
  // in ALLOWED_CONTRACTORS even if they appear in the registry.
  if (!ALLOWED_CONTRACTORS.has(contractor.contractor_id)) {
    return buildRejectionReport({
      execution_id: validated.execution_id,
      reason: `Contractor '${contractor.contractor_id}' is not allowed for execution`,
      startMs,
    });
  }

  // Step 3: Execute via the adapter declared by the matched contractor
  let rawOutput = '';
  let exitCode = 0;
  let timedOut = false;
  let artifact = null;

  try {
    let result;
    if (contractor.adapter === 'openai') {
      result = await executeOpenAIAdapter(validated);
    } else {
      return buildRejectionReport({
        execution_id: validated.execution_id,
        reason: `Unknown adapter type for contractor: ${contractor.contractor_id}`,
        startMs,
      });
    }

    rawOutput = result.rawOutput || '';
    exitCode = result.exitCode ?? 1;
    timedOut = result.timedOut || false;
    artifact = result.artifact || null; // Extract artifact from contractor result
  } catch (err) {
    // Per AGOII–ARTIFACT-SPINE-VALIDATION-002 §STEP-4: Hard fail on missing artifact
    return buildExecutionReport({
      contract: validated,
      status: 'failure',
      exitCode: 1,
      rawOutput: err instanceof Error ? err.message : String(err),
      artifact: null, // Exception = no artifact from contractor
      startMs,
    });
  }

  const status = timedOut ? 'timeout' : exitCode === 0 ? 'success' : 'failure';
  return buildExecutionReport({ 
    contract: validated, 
    status, 
    exitCode, 
    rawOutput, 
    artifact, // Pass contractor-provided artifact
    startMs 
  });
}

// ── Entry point ───────────────────────────────────────────────────────────

(async () => {
  const contractPath = process.argv[2];

  if (!contractPath) {
    process.stderr.write('Error: contract file path is required\n');
    process.stderr.write('Usage: node execute.js <contract-file.json>\n');
    process.exit(1);
  }

  const resolved = path.resolve(contractPath);

  let raw;
  try {
    raw = fs.readFileSync(resolved, 'utf-8');
  } catch (err) {
    process.stderr.write(`Error: cannot read contract file "${resolved}": ${err.message}\n`);
    process.exit(1);
  }

  if (!raw || raw.trim() === '') {
    process.stderr.write(`Error: contract file "${resolved}" is empty\n`);
    process.exit(1);
  }

  let contract;
  try {
    contract = JSON.parse(raw);
  } catch (err) {
    process.stderr.write(`Error: invalid JSON in contract file "${resolved}": ${err.message}\n`);
    process.exit(1);
  }

  if (!contract || typeof contract !== 'object' || Array.isArray(contract)) {
    process.stderr.write('Error: contract must be a non-null JSON object\n');
    process.exit(1);
  }

  // executeContract() never throws — it always returns a structured ExecutionReport.
  const report = await executeContract(contract);

  process.stdout.write(JSON.stringify(report) + '\n');
  process.exit(0);
})().catch((err) => {
  // Safety net: convert any unexpected error to a structured ExecutionReport so
  // callers always receive a well-formed response.
  const report = {
    execution_id: 'unknown',
    status: 'failure',
    exit_code: 1,
    outputs: [],
    artifact: { sections: [] }, // Empty artifact for catastrophic failures
    metadata: {
      contractor_id: 'unknown',
      durationMs: 0,
      timestamp: new Date().toISOString(),
    },
    failure_surface: {
      type: 'execution_error',
      source: 'process',
      details: err instanceof Error ? err.message : String(err),
    },
  };
  process.stdout.write(JSON.stringify(report) + '\n');
  process.exit(1);
});
