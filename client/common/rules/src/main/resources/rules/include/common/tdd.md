# Test-Driven Development

## Design Goals

- Preserve a focused behavioral test's contract from its demonstrated failure before production implementation through
  the implementation and relevant verification that make it pass.
- Make the decision to use TDD explicit before the first production-behavior edit, rather than relying on the task's
  label or the agent's implicit classification.

## Guidance

## Applicability

Before the first edit to production behavior, classify the proposed edit. Use this rule when the edit is intended to
add, correct, remove, or otherwise change observable behavior and a focused automated check is practical. This applies
whether the work is described as a bug fix, feature, remediation, recommended change, regression repair, or
implementation correction; the user's label does not change the trigger.

Do not use this rule for documentation, comments, formatting, or another edit that preserves production behavior. When
an edit is a behavior-preserving refactoring or creates a test seam, first verify the existing focused tests, then
return to the red-test gate before changing behavior. When no focused automated check is practical, state the constraint
and the alternate boundary evidence before editing production behavior.

## Red-Test Gate

Before editing production behavior for a testable outcome, add or update only the focused behavioral test and run its
narrowest command. Report the exact command and the observed behavior-specific failure before making the production
edit. Keep the test-only and production-behavior edits distinct. If the test needs a new seam, first make that
behavior-preserving refactoring separately and verify the existing tests; then create and run the behavioral test. A
compilation, fixture, setup, or unrelated failure does not open the gate.

After the red-test gate opens, implement the smallest change that makes the focused test pass. The initial failure must
demonstrate the intended behavior gap, not a fixture, setup, compilation, or unrelated failure. Do not weaken or rewrite
a valid behavioral test merely to accommodate the implementation. Refactor only while the focused test remains passing.

Start with the minimum observable behavior, then add the next meaningful boundary or variation when coverage is needed.
Generalize the test suite only from the invariant shared by those cases; do not add speculative cases unrelated to the
behavioral contract.

Before handoff, run the narrowest relevant verification and then the complete project verification. If a test cannot be
written first because the behavior exists only at an external boundary, explain the constraint and add the smallest
reliable boundary test after implementing the behavior.
