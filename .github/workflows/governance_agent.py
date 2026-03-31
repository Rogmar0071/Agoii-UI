import sys

changed_files = sys.argv[1].split()

violations = []

# --- RULE 1: ExecutionAuthority boundary ---
for f in changed_files:
    if "CoreBridge" in f:
        with open(f, "r") as file:
            content = file.read()
            if "execute(" in content:
                violations.append("ExecutionAuthority bypass detected in CoreBridge")

# --- RULE 2: Contractor isolation ---
for f in changed_files:
    if "Contractor" in f and "ExecutionAuthority" not in f:
        violations.append("Contractor usage outside ExecutionAuthority")

# --- RULE 3: Ledger flow presence (basic check) ---
ledger_required = ["INTENT_SUBMITTED", "TASK_ASSIGNED", "TASK_EXECUTED"]

for f in changed_files:
    with open(f, "r") as file:
        content = file.read()
        for event in ledger_required:
            if event in content:
                break
        else:
            violations.append(f"Missing ledger flow in {f}")

# --- RESULT ---
if not violations:
    print("STATUS: PASS\nAll invariants satisfied.")
    exit(0)

# --- RECOVERY CONTRACT ---
print("STATUS: FAIL\n")

print("VIOLATIONS:\n")
for v in violations:
    print(f"- {v}")

print("\n---\nRECOVERY CONTRACT:\n")

print("OBJECTIVE:")
print("Restore Agoii execution surface integrity\n")

print("MUTATION SURFACE:")
for f in changed_files:
    print(f"- {f}")

print("\nLOCKED SURFACE:")
print("- Governor.kt")
print("- EventLedger.kt\n")

print("REQUIRED FIXES:")
print("- Route all execution through ExecutionAuthority")
print("- Remove contractor calls outside execution boundary")
print("- Ensure full ledger event chain exists\n")

print("CONSTRAINTS:")
print("- Full file replacement only")
print("- No new architecture")
print("- No scope expansion")
