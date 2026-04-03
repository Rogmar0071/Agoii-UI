// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * Agoii NemoClaw Driver (AGOII–NEMOCLAW-INTEGRATION-001)
 *
 * Exposes a single public function — executeTask() — that is the sole entry
 * point for all controlled execution.  No background loops, autonomous
 * triggers, or internal decision-making are present in this module.
 *
 * Execution flow (AGOII–NEMOCLAW-INTEGRATION-001):
 *
 *   1. Validate payload            (schema/execution_schema.ts — validatePayload)
 *   2. Build NemoClawContract      (execution_id, contractor_id, input.prompt,
 *                                   execution_policy.process.timeoutMs)
 *   3. Send to NemoClaw            (spawn `node execute.js <contract-file>`)
 *   4. Receive ExecutionReport     (parse stdout of execute.js)
 *   5. Validate report (AERP-1)    (assert required fields are present)
 *   6. Map to ContractReport       (unified Agoii result type)
 *
 * Boundary contract:
 *  - ExecutionAuthority triggers ONLY on TASK_STARTED (external caller
 *    responsibility; this driver does not poll or self-trigger).
 *  - NemoClaw is the sole external execution contractor.
 *  - Zero logic leaks across the boundary: the driver constructs the contract
 *    and inspects the report — it does not interpret execution semantics.
 */

import { spawn } from "node:child_process";
import { writeFileSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  validatePayload,
  PayloadValidationError,
  type TaskPayload,
  type ContractReport,
  type ExecutionStatus,
  type NemoClawContract,
  type NemoClawReport,
} from "../schema/execution_schema.js";

// ---------------------------------------------------------------------------
// Locate execute.js
// ---------------------------------------------------------------------------

/**
 * Resolves the absolute path to execute.js.
 *
 * When this module is compiled to agoii/dist/driver/, execute.js is three
 * levels up (agoii/dist/driver → agoii/dist → agoii → repo root).
 * The NEMOCLAW_EXECUTE_PATH environment variable overrides this for testing.
 */
function resolveExecutePath(): string {
  if (process.env["NEMOCLAW_EXECUTE_PATH"]) {
    return process.env["NEMOCLAW_EXECUTE_PATH"];
  }
  // __dirname is agoii/dist/driver/ at runtime; execute.js is at repo root.
  return join(__dirname, "..", "..", "..", "execute.js");
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Builds a NemoClawContract from a validated TaskPayload.
 *
 * Per AGOII–NEMOCLAW-INTEGRATION-001 §STEP-1, ExecutionAuthority always
 * routes through contractor_id "openai-inference".  No capability routing
 * or model selection is delegated to NemoClaw.
 */
function buildNemoClawContract(payload: TaskPayload): NemoClawContract {
  return {
    execution_id: payload.taskId,
    contractor_id: "openai-inference",
    input: {
      prompt: payload.prompt,
    },
    execution_policy: {
      process: {
        timeoutMs: payload.policy.process.timeoutMs,
      },
    },
  };
}

/**
 * Invokes `node execute.js <contract-file>` and returns the raw stdout string.
 * The contract is written to a temporary file to avoid shell-injection risks.
 */
function invokeNemoClaw(
  contract: NemoClawContract,
  executePath: string,
): Promise<{ stdout: string; stderr: string; exitCode: number }> {
  return new Promise((resolve) => {
    // Sanitize execution_id before embedding in the filesystem path to prevent
    // path traversal (e.g. "../..") or special characters from escaping tmpdir.
    const safeId = contract.execution_id.replace(/[^a-zA-Z0-9_-]/g, "_");
    const contractFile = join(tmpdir(), `nemoclaw-contract-${safeId}-${Date.now()}.json`);
    writeFileSync(contractFile, JSON.stringify(contract), "utf-8");

    let stdout = "";
    let stderr = "";
    let settled = false;

    const child = spawn(process.execPath, [executePath, contractFile], {
      env: { ...process.env },
    });

    child.stdout.on("data", (chunk: Buffer) => { stdout += chunk.toString(); });
    child.stderr.on("data", (chunk: Buffer) => { stderr += chunk.toString(); });

    const finish = (code: number | null) => {
      if (settled) return;
      settled = true;
      try { unlinkSync(contractFile); } catch (cleanupErr) {
        process.stderr.write(`[nemoclaw-driver] Warning: failed to remove temp contract file "${contractFile}": ${cleanupErr instanceof Error ? cleanupErr.message : String(cleanupErr)}\n`);
      }
      resolve({ stdout, stderr, exitCode: code ?? 1 });
    };

    child.on("close", finish);
    child.on("error", (err) => {
      if (settled) return;
      settled = true;
      try { unlinkSync(contractFile); } catch (cleanupErr) {
        process.stderr.write(`[nemoclaw-driver] Warning: failed to remove temp contract file "${contractFile}": ${cleanupErr instanceof Error ? cleanupErr.message : String(cleanupErr)}\n`);
      }
      resolve({ stdout: "", stderr: err.message, exitCode: 1 });
    });
  });
}

/**
 * Validates the NemoClawReport against AERP-1 (Agoii Execution Report
 * Protocol v1): asserts that the mandatory top-level fields are present and
 * well-typed, and that execution_id echoes back the expected value for
 * correlation (prevents spoofed or misrouted reports from being accepted).
 *
 * Throws a plain Error on violation.
 */
function validateReport(raw: unknown, expectedExecutionId: string): NemoClawReport {
  if (typeof raw !== "object" || raw === null) {
    throw new Error("AERP-1: report must be a non-null object");
  }
  const r = raw as Record<string, unknown>;
  if (typeof r["execution_id"] !== "string") {
    throw new Error("AERP-1: report.execution_id must be a string");
  }
  if (r["execution_id"] !== expectedExecutionId) {
    throw new Error(
      `AERP-1: report.execution_id "${r["execution_id"]}" does not match expected "${expectedExecutionId}"`,
    );
  }
  if (typeof r["status"] !== "string") {
    throw new Error("AERP-1: report.status must be a string");
  }
  if (typeof r["exit_code"] !== "number") {
    throw new Error("AERP-1: report.exit_code must be a number");
  }
  if (!Array.isArray(r["outputs"])) {
    throw new Error("AERP-1: report.outputs must be an array");
  }
  if (typeof r["metadata"] !== "object" || r["metadata"] === null) {
    throw new Error("AERP-1: report.metadata must be a non-null object");
  }
  return raw as NemoClawReport;
}

/**
 * Maps a NemoClawReport to the Agoii-internal ContractReport that
 * ExecutionAuthority consumes.  This is the only place where NemoClaw's
 * report schema is translated into Agoii's type system.
 */
function mapToContractReport(
  report: NemoClawReport,
  startMs: number,
): ContractReport {
  const now = new Date();
  const statusMap: Record<string, ExecutionStatus> = {
    success: "success",
    failure: "failure",
    timeout: "timeout",
    rejected: "failure",
  };
  const statusSignal: ExecutionStatus = statusMap[report.status] ?? "failure";

  return {
    executionId: report.execution_id,
    statusSignal,
    rawOutput: report.outputs.map((o) => o.content).join("\n"),
    normalizedOutput: report.outputs,
    exitCode: report.exit_code,
    policyViolations: [],
    failureSurface:
      report.failure_surface !== undefined
        ? {
            type:
              report.failure_surface.type === "timeout" ||
              report.failure_surface.type === "execution_error" ||
              report.failure_surface.type === "policy_violation"
                ? report.failure_surface.type
                : "execution_error",
            source:
              report.failure_surface.source === "sandbox" ||
              report.failure_surface.source === "policy" ||
              report.failure_surface.source === "process"
                ? report.failure_surface.source
                : "process",
            details: report.failure_surface.details,
          }
        : undefined,
    metadata: {
      durationMs: now.getTime() - startMs,
      sandboxId: "",
      timestamp: now.toISOString(),
    },
  };
}

/**
 * Builds a ContractReport for pre-execution error paths (validation failures,
 * NemoClaw invocation failures, AERP-1 violations) where no ExecutionReport
 * was produced.
 */
function buildErrorReport(
  executionId: string,
  details: string,
  startMs: number,
): ContractReport {
  const now = new Date();
  return {
    executionId,
    statusSignal: "failure",
    rawOutput: "",
    normalizedOutput: [],
    exitCode: 1,
    policyViolations: [],
    failureSurface: {
      type: "execution_error",
      source: "process",
      details,
    },
    metadata: {
      durationMs: now.getTime() - startMs,
      sandboxId: "",
      timestamp: now.toISOString(),
    },
  };
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * executeTask — the single controlled entry point for all NemoClaw task
 * execution under the Agoii contract.
 *
 * Triggered ONLY by TASK_STARTED; never self-triggered or called by Governor.
 *
 * @param taskPayload  Raw (unvalidated) task payload supplied by the external
 *                     caller.  Validation is the first action performed.
 * @returns            A ContractReport exposing ALL execution signals so that
 *                     ExecutionAuthority retains full judgment authority.
 *
 * Contract guarantees:
 *  - Never throws; all error paths return a ContractReport with
 *    statusSignal "failure" and a defined failureSurface.
 *  - NemoClaw is the sole external execution path; no direct sandbox or
 *    CLI invocations are performed by this driver.
 *  - The returned report is always validated against AERP-1 before mapping.
 */
export async function executeTask(taskPayload: unknown): Promise<ContractReport> {
  const startMs = Date.now();

  // ── Step 1: Validate payload ──────────────────────────────────────────────
  let payload: TaskPayload;
  try {
    payload = validatePayload(taskPayload);
  } catch (err) {
    const details =
      err instanceof PayloadValidationError
        ? err.message
        : `Payload validation failed: ${String(err)}`;
    const rawId =
      typeof taskPayload === "object" &&
      taskPayload !== null &&
      "taskId" in taskPayload
        ? String((taskPayload as Record<string, unknown>)["taskId"])
        : "unknown";
    return buildErrorReport(rawId, details, startMs);
  }

  // ── Step 2: Build NemoClawContract ────────────────────────────────────────
  const contract = buildNemoClawContract(payload);
  const executePath = resolveExecutePath();

  // ── Step 3: Send to NemoClaw ──────────────────────────────────────────────
  let invokeResult: { stdout: string; stderr: string; exitCode: number };
  try {
    invokeResult = await invokeNemoClaw(contract, executePath);
  } catch (err) {
    const details = `NemoClaw invocation failed: ${err instanceof Error ? err.message : String(err)}`;
    return buildErrorReport(payload.taskId, details, startMs);
  }

  // ── Step 4: Receive ExecutionReport ───────────────────────────────────────
  let rawReport: unknown;
  try {
    rawReport = JSON.parse(invokeResult.stdout);
  } catch {
    const details = `NemoClaw returned non-JSON output: ${invokeResult.stdout.slice(0, 200)}`;
    return buildErrorReport(payload.taskId, details, startMs);
  }

  // ── Step 5: Validate report (AERP-1) ─────────────────────────────────────
  let nemoReport: NemoClawReport;
  try {
    nemoReport = validateReport(rawReport, contract.execution_id);
  } catch (err) {
    const details = err instanceof Error ? err.message : String(err);
    return buildErrorReport(payload.taskId, details, startMs);
  }

  // ── Step 6: Map to ContractReport → emit TASK_EXECUTED ───────────────────
  return mapToContractReport(nemoReport, startMs);
}


