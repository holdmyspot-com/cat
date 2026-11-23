# Non-Retryable Turn Errors

## Design Goals

- Before an operation that may cross a safeguard, choose independently permitted work that preserves the required
  outcome and acceptance evidence wherever possible. Record any outcome or evidence the permitted work cannot establish.
- When assembling a request directly or changing how CAT constructs it, verify the resulting request against the
  receiving interface and correct known input errors before submission.
- After a safeguard stops work or a non-retryable error ends a turn, preserve the affected boundary and continue
  independently permitted work when execution is available.

## Guidance

Before acting on an operation whose effects or required access may cross an applicable safety or authorization
boundary, identify what the operation would do, which boundary governs it, and what evidence the task requires.
Proceed if the operation is permitted. Otherwise, use an independently permitted method if it preserves the boundary
and meets the same acceptance criteria without performing the restricted operation or relying on its output.
For example, use a controlled interruption in a disposable fixture to verify recovery when the test requires proof
of recovery behavior and the fixture can supply that evidence. Evaluate the effects of a proposed alternative.
Changes to wording, tools, or delegation leave an operation's authority requirements in force.

If a permitted method covers only part of the outcome, complete that part and record which acceptance criteria remain
unverified. If permission or equivalence is unresolved, leave the affected operation pending and advance independent work.

When assembling a new harness or provider request directly, or changing code or configuration that constructs it,
check the resulting request against the receiving interface's documented input requirements before sending it.
Correct known incompatibilities before sending. If an interface requirement cannot be established, leave the affected
request pending and report what must be verified. A routine call through an existing request builder with supported
inputs requires no additional request check under this rule.

If a safeguard stops an operation, continue independently permitted work while execution remains available. For a
suspected false positive, use the safeguard's supported review path if available. Resume the affected operation only
after the responsible authority clears it. When permitted work is eligible for delegation after a cybersecurity stop,
lazy load `${CAT_PLUGIN_ROOT}/rules/include/common/delegation.md`.

If a non-retryable policy or invalid-request error ends the turn, apply this guidance when a later turn receives the
error or its record. Preserve the diagnostic and unfinished acceptance criteria. Leave a policy-stopped operation
pending review. For an invalid request, correct the identified input defect against the documented interface before
submitting a corrected request. Continue independently permitted work when the later turn is available.
