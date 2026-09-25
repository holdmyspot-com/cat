# Test-Driven Development

## Design Goals

- Preserve a focused behavioral test's contract from its demonstrated failure before production implementation through
  the implementation and relevant verification that make it pass.
- Make the decision to use TDD explicit before the first production-behavior edit, rather than relying on the task's
  label or the agent's implicit classification.
<!--
In the campaign-recovery fix, one failing lookup test preceded the first edit, but a later ownership check and a
resumption-path change were made without failing tests that exercised those behaviors. Preserve a red gate for each
distinct testable outcome, including one discovered after implementation begins.
-->
- Keep each distinct testable behavior change tied to a failure observed at the boundary that the change claims to fix.
- Ground test expectations in established behavior contracts so that test-first work does not entrench an unresolved
  design premise.

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

Before encoding expectations, establish the required behavior and claimed test boundary from authoritative contracts or
accepted criteria. When an unresolved premise could change either, lazy load `monotonic-progress.md` and apply its
premise-check requirements before dependent assertions or implementation. Keep independent ready behavior eligible for
its red-test gate. A mock configured to assume an external guarantee cannot establish that guarantee; it may exercise
local behavior under an already established contract.

## Red-Test Gate

Before editing production behavior for a testable outcome, add or update only the focused behavioral test and run its
narrowest command. Report the exact command and the observed behavior-specific failure before making the production
edit. Keep the test-only and production-behavior edits distinct. If the test needs a new seam, first make that
behavior-preserving refactoring separately and verify the existing tests; then create and run the behavioral test. A
compilation, fixture, setup, or unrelated failure does not open the gate. Compilation or setup failures establish only
that the behavioral check is not yet runnable; resolve that prerequisite and obtain a behavior-specific failure.

After the red-test gate opens, implement the smallest change that makes the focused test pass. The initial failure must
demonstrate the intended behavior gap, not a fixture, setup, compilation, or unrelated failure. Do not weaken or rewrite
a valid behavioral test merely to accommodate the implementation. Refactor only while the focused test remains passing.

Before each later production-behavior edit, check whether the recorded red test exercises the newly changed outcome at
its claimed boundary. If it does not, add and run a focused test that fails for that outcome before the edit. A test
that first passes after the edit is coverage, not a red-test receipt. A helper's failing test does not establish the
behavior of a caller that cannot yet reach the helper. Make a testable path to the claimed boundary when practical;
otherwise leave that outcome unverified and state the specific constraint. A locally dormant or always-rejecting entry
point is not an external boundary that excuses a testable behavior change from this gate.

Start with the minimum observable behavior, then add the next meaningful boundary or variation when coverage is needed.
Generalize the test suite only from the invariant shared by those cases; do not add speculative cases unrelated to the
behavioral contract.

Before handoff, run the narrowest relevant verification and then the complete project verification. When established
required behavior can be exercised only at an external boundary that needs enabling implementation, explain the missing
test boundary before that work. Limit the enabling work to what makes the boundary check possible, then add and run the
smallest reliable boundary test at that first opportunity. This exception does not establish an unresolved external
contract or waive the red-test gate for independently testable behavior.
