// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * Agoii Execution Adapter
 *
 * Provides three focused responsibilities:
 *
 *  1. SandboxController — on-demand lifecycle management (initialize / terminate).
 *  2. PolicyEnforcer     — maps ExecutionPolicy rules to runtime constraints and
 *                          validates that a task result did not violate them.
 *  3. OutputNormalizer   — converts raw agent text into the deterministic
 *                          ExecutionOutput[] structure required by ExecutionResult.
 *
 * None of these components start autonomously; all public methods are invoked
 * only by an explicit external call through the driver.
 */

import { exec } from "node:child_process";
import { promisify } from "node:util";
import type {
  ExecutionOutput,
  ExecutionPolicy,
  ExecutionStatus,
  NetworkPolicy,
  FilesystemPolicy,
  ProcessPolicy,
} from "../schema/execution_schema.js";

const execAsync = promisify(exec);

// ---------------------------------------------------------------------------
// Sandbox Controller
// ---------------------------------------------------------------------------

/** Opaque handle representing a running sandbox instance. */
export interface SandboxHandle {
  /** Name of the sandbox as known to OpenShell. */
  sandboxName: string;
  /** ISO-8601 timestamp when the sandbox was started. */
  startedAt: string;
}

/** Options supplied when creating a sandbox. */
export interface SandboxOptions {
  /** Name to assign to the sandbox container. */
  sandboxName: string;
  /**
   * Maximum wall-clock time in milliseconds the sandbox is permitted to run.
   * The controller uses this to enforce an outer deadline independent of the
   * OpenShell daemon.
   */
  timeoutMs: number;
}

/**
 * Manages the lifecycle of an OpenShell sandbox on demand.
 *
 * The controller is stateless between calls; a new SandboxHandle is returned
 * each time initialize() succeeds and must be passed back to terminate().
 */
export class SandboxController {
  /**
   * Initializes a new sandbox and returns a handle to it.
   *
   * Uses `openshell sandbox start` under the hood, which is already available
   * in the NemoClaw runtime environment.  The call is synchronous from the
   * driver's perspective — it awaits sandbox readiness before returning.
   */
  async initialize(options: SandboxOptions): Promise<SandboxHandle> {
    const { sandboxName, timeoutMs } = options;

    // Derive a safe openshell timeout flag (floor at 1 s, round up to seconds).
    const timeoutSec = Math.max(1, Math.ceil(timeoutMs / 1000));

    await execAsync(
      `openshell sandbox start ${sandboxName} --wait --timeout ${timeoutSec}`,
      { timeout: timeoutMs + 5_000 },
    );

    return {
      sandboxName,
      startedAt: new Date().toISOString(),
    };
  }

  /**
   * Terminates the sandbox identified by handle.
   *
   * Always resolves — errors during teardown are surfaced as a warning string
   * rather than thrown, so the driver can still return a result to the caller
   * even if cleanup fails.
   */
  async terminate(handle: SandboxHandle): Promise<{ warning?: string }> {
    try {
      await execAsync(`openshell sandbox stop ${handle.sandboxName} --force`, {
        timeout: 15_000,
      });
      return {};
    } catch (err) {
      const warning = err instanceof Error ? err.message : String(err);
      return { warning: `Sandbox teardown warning: ${warning}` };
    }
  }
}

// ---------------------------------------------------------------------------
// Policy Enforcer
// ---------------------------------------------------------------------------

/** Violation detected by the policy enforcer. */
export interface PolicyViolation {
  rule: string;
  detail: string;
}

/**
 * Maps ExecutionPolicy declarations to concrete runtime constraints and checks
 * that a completed execution did not overstep them.
 *
 * The enforcer operates in two phases:
 *  - constraints() — called before execution to produce an environment-variable
 *    map that OpenShell / the agent runtime can consume.
 *  - audit()       — called after execution to verify observed behaviour
 *    against the declared policy.
 */
export class PolicyEnforcer {
  // --- Network ---------------------------------------------------------------

  private mapNetwork(policy: NetworkPolicy): Record<string, string> {
    const env: Record<string, string> = {};
    env["AGOII_NETWORK_OUTBOUND"] = policy.allowOutbound ? "1" : "0";
    if (policy.allowOutbound && policy.allowedHosts.length > 0) {
      env["AGOII_NETWORK_ALLOWED_HOSTS"] = policy.allowedHosts.join(",");
    }
    if (!policy.allowOutbound) {
      // Signal to the sandbox bootstrap that the network namespace should be
      // restricted.  The actual enforcement is delegated to the OpenShell
      // sandbox runtime via this well-known environment variable.
      env["AGOII_NETWORK_MODE"] = "none";
    }
    return env;
  }

  // --- Filesystem ------------------------------------------------------------

  private mapFilesystem(policy: FilesystemPolicy): Record<string, string> {
    const env: Record<string, string> = {};
    env["AGOII_FS_WRITES"] = policy.allowWrites ? "1" : "0";
    if (policy.allowedReadPaths.length > 0) {
      env["AGOII_FS_READ_PATHS"] = policy.allowedReadPaths.join(":");
    }
    if (policy.allowWrites && policy.allowedWritePaths.length > 0) {
      env["AGOII_FS_WRITE_PATHS"] = policy.allowedWritePaths.join(":");
    }
    return env;
  }

  // --- Process ---------------------------------------------------------------

  private mapProcess(policy: ProcessPolicy): Record<string, string> {
    const env: Record<string, string> = {};
    env["AGOII_PROC_SUBPROCESSES"] = policy.allowSubprocesses ? "1" : "0";
    env["AGOII_PROC_TIMEOUT_MS"] = String(policy.timeoutMs);
    if (policy.maxMemoryMb > 0) {
      env["AGOII_PROC_MAX_MEMORY_MB"] = String(policy.maxMemoryMb);
    }
    return env;
  }

  /**
   * Produces a flat environment-variable map that encodes all policy
   * constraints.  The map is passed to the sandbox so that the OpenShell
   * runtime can apply the relevant restrictions before the task begins.
   */
  constraints(policy: ExecutionPolicy): Record<string, string> {
    return {
      ...this.mapNetwork(policy.network),
      ...this.mapFilesystem(policy.filesystem),
      ...this.mapProcess(policy.process),
    };
  }

  /**
   * Audits the raw output of a completed execution against the declared policy.
   * Returns an array of violations; an empty array means no violations were
   * detected.
   *
   * Currently validates:
   *  - Outbound network calls are absent when allowOutbound is false.
   *  - Filesystem writes are absent when allowWrites is false.
   *  - Subprocess spawning is absent when allowSubprocesses is false.
   *
   * Detection relies on well-known sentinel strings emitted by the OpenShell
   * sandbox runtime into stderr/stdout.
   */
  audit(rawOutput: string, policy: ExecutionPolicy): PolicyViolation[] {
    const violations: PolicyViolation[] = [];

    if (!policy.network.allowOutbound && rawOutput.includes("AGOII_VIOLATION:NETWORK")) {
      violations.push({
        rule: "network.allowOutbound",
        detail: "Sandbox attempted an outbound network connection while policy disallows it.",
      });
    }

    if (!policy.filesystem.allowWrites && rawOutput.includes("AGOII_VIOLATION:FS_WRITE")) {
      violations.push({
        rule: "filesystem.allowWrites",
        detail: "Sandbox attempted a filesystem write while policy disallows it.",
      });
    }

    if (!policy.process.allowSubprocesses && rawOutput.includes("AGOII_VIOLATION:SUBPROCESS")) {
      violations.push({
        rule: "process.allowSubprocesses",
        detail: "Sandbox attempted to spawn a subprocess while policy disallows it.",
      });
    }

    return violations;
  }
}

// ---------------------------------------------------------------------------
// Output Normalizer
// ---------------------------------------------------------------------------

/**
 * Converts raw, potentially multi-line text produced by the OpenClaw agent
 * into the deterministic ExecutionOutput[] structure.
 *
 * Guarantees:
 *  - No raw log prefixes (timestamps, log levels) are forwarded.
 *  - Content is trimmed of leading/trailing whitespace.
 *  - An empty or whitespace-only raw string yields an empty array.
 *  - All returned items have contentType "text/plain".
 */
export class OutputNormalizer {
  /**
   * Pattern that matches common log-level prefixes emitted by PluginLogger
   * (e.g. "[INFO] …", "[WARN] …", "2026-03-31T12:00:00Z INFO …").
   */
  private static readonly LOG_PREFIX =
    /^(?:\d{4}-\d{2}-\d{2}T[\d:.]+Z\s+)?(?:INFO|WARN|ERROR|DEBUG)\s+/i;

  /**
   * Strips log-level prefixes from a single line.
   */
  private stripLogPrefix(line: string): string {
    return line.replace(OutputNormalizer.LOG_PREFIX, "");
  }

  /**
   * Normalises a raw output string and determines the terminal execution status.
   *
   * @param rawOutput   Raw stdout/stderr captured from the agent process.
   * @param timedOut    True when the execution was forcibly stopped due to timeout.
   * @param violations  Policy violations detected by PolicyEnforcer.audit().
   */
  normalize(
    rawOutput: string,
    timedOut: boolean,
    violations: PolicyViolation[],
    exitFailed: boolean,
  ): { status: ExecutionStatus; outputs: ExecutionOutput[] } {
    if (timedOut) {
      return { status: "timeout", outputs: [] };
    }

    if (violations.length > 0) {
      return { status: "policy_violation", outputs: [] };
    }

    if (exitFailed) {
      return { status: "failure", outputs: [] };
    }

    const trimmed = rawOutput.trim();
    if (trimmed === "") {
      return { status: "success", outputs: [] };
    }

    // Split on newlines, strip log prefixes, discard empty lines.
    const lines = trimmed
      .split(/\r?\n/)
      .map((l) => this.stripLogPrefix(l).trimEnd())
      .filter((l) => l.length > 0);

    if (lines.length === 0) {
      return { status: "success", outputs: [] };
    }

    const content = lines.join("\n");
    const output: ExecutionOutput = { contentType: "text/plain", content };
    return { status: "success", outputs: [output] };
  }
}
