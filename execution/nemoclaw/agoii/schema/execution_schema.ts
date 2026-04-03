// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

/**
 * Agoii Execution Schema
 *
 * Defines the canonical structured types for all inputs and outputs flowing
 * through the NemoClaw controlled execution environment.  Nothing in this
 * file produces side-effects; it is a pure type and constant definition
 * module.
 */

// ---------------------------------------------------------------------------
// Task Payload (input)
// ---------------------------------------------------------------------------

/** Allowed task kinds that the controlled environment will accept. */
export type TaskKind = "query" | "code" | "review" | "summarize" | "custom";

/** Network-level policy constraints. */
export interface NetworkPolicy {
  /** Allow outbound network calls from the sandbox. Default: false. */
  allowOutbound: boolean;
  /** Explicit allowlist of hostnames the sandbox may reach (requires allowOutbound). */
  allowedHosts: string[];
}

/** Filesystem-level policy constraints. */
export interface FilesystemPolicy {
  /** Allow writes to the sandbox filesystem. Default: false. */
  allowWrites: boolean;
  /**
   * Absolute path prefixes inside the sandbox that the task is permitted to
   * read.  An empty list means no filesystem reads are allowed.
   */
  allowedReadPaths: string[];
  /**
   * Absolute path prefixes inside the sandbox that the task is permitted to
   * write (only relevant when allowWrites is true).
   */
  allowedWritePaths: string[];
}

/** Process-level policy constraints. */
export interface ProcessPolicy {
  /** Allow the task to spawn child processes. Default: false. */
  allowSubprocesses: boolean;
  /** Maximum wall-clock execution time in milliseconds. 0 means no limit. */
  timeoutMs: number;
  /** Maximum memory the sandbox process may consume in megabytes. 0 means no limit. */
  maxMemoryMb: number;
}

/**
 * Execution policy applied to a single task run.
 * All three sub-policies are required so that callers must make an explicit
 * choice for every constraint axis rather than relying on implicit defaults.
 */
export interface ExecutionPolicy {
  network: NetworkPolicy;
  filesystem: FilesystemPolicy;
  process: ProcessPolicy;
}

/**
 * Structured payload that an external caller submits to executeTask().
 *
 * Design principles:
 *  - Every field is explicit; nothing is inferred from environment state.
 *  - The policy sub-object fully determines what the sandbox is permitted to do.
 *  - context is an opaque key/value bag for task-specific metadata; the driver
 *    does not inspect it.
 */
export interface TaskPayload {
  /** Caller-assigned identifier used to correlate results with requests. */
  taskId: string;
  /** Semantic category of the task. */
  kind: TaskKind;
  /** Natural-language or structured prompt to inject into the OpenClaw agent. */
  prompt: string;
  /** Model identifier to use for this task (e.g. "nvidia/nemotron-3-super-120b-a12b"). */
  model: string;
  /** Policy constraints for the sandbox that executes this task. */
  policy: ExecutionPolicy;
  /** Optional caller-controlled key/value metadata forwarded verbatim in the result. */
  context?: Record<string, string>;
}

// ---------------------------------------------------------------------------
// Execution Result (output)
// ---------------------------------------------------------------------------

/** Terminal status of a task execution. */
export type ExecutionStatus = "success" | "failure" | "timeout" | "policy_violation";

/**
 * A policy violation detected by the PolicyEnforcer after execution.
 * Defined here as the canonical source; the adapter's identical interface is
 * structurally compatible via TypeScript's structural type system.
 */
export interface PolicyViolation {
  rule: string;
  detail: string;
}

/**
 * Describes the surface at which an execution failure occurred.
 * Returned inside ContractReport so that ExecutionAuthority can issue a
 * targeted recovery contract without re-inspecting raw output.
 */
export interface FailureSurface {
  type: "timeout" | "policy_violation" | "execution_error";
  source: "sandbox" | "policy" | "process";
  details: string;
}

/**
 * Structured report returned by executeTask() under the Agoii execution
 * authority model.
 *
 * ALL signals are preserved — rawOutput, exitCode, policyViolations — so that
 * ExecutionAuthority retains full judgment authority.  The driver does NOT
 * collapse or suppress any signal.
 */
export interface ContractReport {
  /** Echoed back from TaskPayload.taskId for correlation. */
  executionId: string;
  /** Terminal status signal produced by the OutputNormalizer. */
  statusSignal: ExecutionStatus;
  /** Unmodified raw output captured from the agent process. */
  rawOutput: string;
  /** Normalized output produced by the OutputNormalizer. */
  normalizedOutput: ExecutionOutput[];
  /** Process exit code returned by the agent; 0 on success. */
  exitCode: number;
  /** Policy violations detected by the PolicyEnforcer after execution. */
  policyViolations: PolicyViolation[];
  /**
   * Populated whenever the execution did not complete successfully.
   * Undefined on clean success (no violations, zero exit code, no timeout).
   */
  failureSurface?: FailureSurface;
  /** Execution timing and sandbox identity metadata. */
  metadata: {
    /** Wall-clock duration from payload receipt to result finalization, in ms. */
    durationMs: number;
    /** Name of the OpenShell sandbox that ran the task; empty when no sandbox was started. */
    sandboxId: string;
    /** ISO-8601 timestamp at which the report was finalized. */
    timestamp: string;
  };
}

/**
 * A single normalized output unit produced by the OpenClaw agent.
 * Only text content is supported; raw byte streams are not permitted.
 */
export interface ExecutionOutput {
  /** MIME type of the content (always "text/plain" in v1). */
  contentType: "text/plain";
  /** The normalized, trimmed text content. */
  content: string;
}

/**
 * Structured, deterministic result returned by executeTask().
 *
 * All fields are always present so downstream consumers can pattern-match on
 * status without optional-chaining into undefined values.
 */
export interface ExecutionResult {
  /** Echoed back from the originating TaskPayload for correlation. */
  taskId: string;
  /** Terminal status of the execution. */
  status: ExecutionStatus;
  /** Normalized output produced by the agent (empty array on failure). */
  outputs: ExecutionOutput[];
  /**
   * Human-readable error message when status is not "success".
   * Empty string when status is "success".
   */
  errorMessage: string;
  /** ISO-8601 timestamp at which the driver received the task payload. */
  startedAt: string;
  /** ISO-8601 timestamp at which the result was finalized. */
  completedAt: string;
  /** Echoed back from TaskPayload.context for caller correlation. */
  context: Record<string, string>;
}

// ---------------------------------------------------------------------------
// NemoClaw Integration Contract (AGOII–NEMOCLAW-INTEGRATION-001)
// ---------------------------------------------------------------------------

/**
 * Execution contract that ExecutionAuthority constructs and passes to NemoClaw.
 *
 * This is the canonical boundary type between Agoii and NemoClaw.  No logic
 * may be inferred or defaulted by NemoClaw — every field must be explicitly
 * set by the caller (ExecutionAuthority).
 *
 * Contract shape per AGOII–NEMOCLAW-INTEGRATION-001 §STEP-1:
 *   { execution_id, contractor_id, input: { prompt }, execution_policy.process.timeoutMs }
 */
export interface NemoClawContract {
  /** Caller-assigned identifier used to correlate the ExecutionReport with this request. */
  execution_id: string;
  /**
   * ID of the contractor to invoke.  Must be present in NemoClaw's registry
   * and in its ALLOWED_CONTRACTORS set; any other value is rejected by NemoClaw
   * before execution begins.
   */
  contractor_id: string;
  /** Input payload for the contractor. */
  input: {
    /** Natural-language prompt forwarded to the OpenAI inference contractor. */
    prompt: string;
  };
  /** Execution constraints enforced by NemoClaw. */
  execution_policy: {
    process: {
      /** Maximum wall-clock execution time in milliseconds. */
      timeoutMs: number;
    };
  };
}

/**
 * A single section within an execution artifact.
 * Each section represents a discrete, content-addressable unit of output.
 */
export interface ArtifactSection {
  /** Unique identifier for this section within the artifact. */
  section_id: string;
  /** The actual content of this section. */
  content: string;
  /** SHA-256 hash of the content for integrity verification. */
  content_hash: string;
}

/**
 * Deterministic artifact produced by every execution.
 * Enables structural validation, delta enforcement, and regression detection.
 * Per AGOII–ARTIFACT-SPINE-001.
 */
export interface ExecutionArtifact {
  /** Ordered array of artifact sections. Must contain at least one section. */
  sections: ArtifactSection[];
}

/**
 * Structured report returned by NemoClaw after executing a NemoClawContract.
 *
 * ExecutionAuthority receives this report and validates it per AERP-1 before
 * emitting TASK_EXECUTED.
 */
export interface NemoClawReport {
  /** Echoed back from NemoClawContract.execution_id for correlation. */
  execution_id: string;
  /** Terminal status of the execution. */
  status: "success" | "failure" | "timeout" | "rejected";
  /** Process exit code; 0 on success. */
  exit_code: number;
  /** Normalized output items produced by the contractor. */
  outputs: Array<{ contentType: "text/plain"; content: string }>;
  /**
   * Deterministic artifact produced by this execution.
   * MANDATORY per AGOII–ARTIFACT-SPINE-001.
   * Missing or invalid artifact causes execution failure.
   */
  artifact: ExecutionArtifact;
  /** Execution metadata. */
  metadata: {
    /** contractor_id that was resolved and executed (or "unknown" on rejection). */
    contractor_id: string;
    /** Wall-clock duration from contract receipt to report finalization, in ms. */
    durationMs: number;
    /** ISO-8601 timestamp at which the report was finalized. */
    timestamp: string;
  };
  /**
   * Populated when execution did not complete successfully.
   * Absent on clean success.
   */
  failure_surface?: {
    type: "timeout" | "execution_error" | "contract_rejection" | "policy_violation" | "missing_artifact" | "invalid_artifact";
    source: "process" | "sandbox" | "validator" | "policy" | "artifact";
    details: string;
  };
}

/**
 * Returns a maximally-restrictive default policy.
 * Callers MUST explicitly relax any constraint they need; nothing is open by
 * default.
 */
export function defaultPolicy(): ExecutionPolicy {
  return {
    network: {
      allowOutbound: false,
      allowedHosts: [],
    },
    filesystem: {
      allowWrites: false,
      allowedReadPaths: [],
      allowedWritePaths: [],
    },
    process: {
      allowSubprocesses: false,
      timeoutMs: 30_000,
      maxMemoryMb: 512,
    },
  };
}

// ---------------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------------

/** Thrown when an incoming TaskPayload fails schema validation. */
export class PayloadValidationError extends Error {
  constructor(
    public readonly field: string,
    message: string,
  ) {
    super(`PayloadValidationError [${field}]: ${message}`);
    this.name = "PayloadValidationError";
  }
}

/**
 * Validates a raw TaskPayload and throws PayloadValidationError on the first
 * invalid field found.  Returns the payload unmodified on success so it can be
 * used in a pipeline (validate → use).
 */
export function validatePayload(payload: unknown): TaskPayload {
  if (typeof payload !== "object" || payload === null) {
    throw new PayloadValidationError("payload", "must be a non-null object");
  }

  const p = payload as Record<string, unknown>;

  if (typeof p["taskId"] !== "string" || p["taskId"].trim() === "") {
    throw new PayloadValidationError("taskId", "must be a non-empty string");
  }

  const allowedKinds: TaskKind[] = ["query", "code", "review", "summarize", "custom"];
  if (!allowedKinds.includes(p["kind"] as TaskKind)) {
    throw new PayloadValidationError("kind", `must be one of: ${allowedKinds.join(", ")}`);
  }

  if (typeof p["prompt"] !== "string" || p["prompt"].trim() === "") {
    throw new PayloadValidationError("prompt", "must be a non-empty string");
  }

  if (typeof p["model"] !== "string" || p["model"].trim() === "") {
    throw new PayloadValidationError("model", "must be a non-empty string");
  }

  if (typeof p["policy"] !== "object" || p["policy"] === null) {
    throw new PayloadValidationError("policy", "must be a non-null object");
  }

  const pol = p["policy"] as Record<string, unknown>;

  for (const sub of ["network", "filesystem", "process"] as const) {
    if (typeof pol[sub] !== "object" || pol[sub] === null) {
      throw new PayloadValidationError(`policy.${sub}`, "must be a non-null object");
    }
  }

  const net = pol["network"] as Record<string, unknown>;
  if (typeof net["allowOutbound"] !== "boolean") {
    throw new PayloadValidationError("policy.network.allowOutbound", "must be a boolean");
  }
  if (!Array.isArray(net["allowedHosts"])) {
    throw new PayloadValidationError("policy.network.allowedHosts", "must be an array");
  }

  const fs = pol["filesystem"] as Record<string, unknown>;
  if (typeof fs["allowWrites"] !== "boolean") {
    throw new PayloadValidationError("policy.filesystem.allowWrites", "must be a boolean");
  }
  if (!Array.isArray(fs["allowedReadPaths"])) {
    throw new PayloadValidationError("policy.filesystem.allowedReadPaths", "must be an array");
  }
  if (!Array.isArray(fs["allowedWritePaths"])) {
    throw new PayloadValidationError("policy.filesystem.allowedWritePaths", "must be an array");
  }

  const proc = pol["process"] as Record<string, unknown>;
  if (typeof proc["allowSubprocesses"] !== "boolean") {
    throw new PayloadValidationError("policy.process.allowSubprocesses", "must be a boolean");
  }
  if (typeof proc["timeoutMs"] !== "number" || proc["timeoutMs"] < 0) {
    throw new PayloadValidationError("policy.process.timeoutMs", "must be a non-negative number");
  }
  if (typeof proc["maxMemoryMb"] !== "number" || proc["maxMemoryMb"] < 0) {
    throw new PayloadValidationError("policy.process.maxMemoryMb", "must be a non-negative number");
  }

  if (p["context"] !== undefined) {
    if (typeof p["context"] !== "object" || p["context"] === null || Array.isArray(p["context"])) {
      throw new PayloadValidationError("context", "must be a plain object when provided");
    }
    const ctx = p["context"] as Record<string, unknown>;
    for (const [k, v] of Object.entries(ctx)) {
      if (typeof v !== "string") {
        throw new PayloadValidationError(`context.${k}`, "every value must be a string");
      }
    }
  }

  return payload as TaskPayload;
}
