# Prompt Hardening

## Design Goals

- Improve a prompt-compliance failure through independent challenge while retaining deterministic tooling failures at
  their owning implementation boundary.

Classify evidence before editing. Repair a reproducible validator, packager, launcher, or environment failure with its
deterministic owner. For a delivered and adequate prompt that an executor misapplies, obtain an independent challenge
of the requirement, test, and proposed change; use its evidence to revise the earliest prompt decision. Keep the
challenger’s inputs limited to the artifact, task, evidence, and acceptance condition, never the intended answer.

When several failures share the same owner and invariant, challenge the compatible repair set together. A finding can
reopen the work only when it identifies an unchecked acceptance condition or counterexample; record its evidence and
the resulting owner decision before another rerun.
