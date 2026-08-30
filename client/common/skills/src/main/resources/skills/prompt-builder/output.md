# Prompt-Builder Output

## Design Goals

- Let a user see the current decision, evidence, and next action without internal execution noise obscuring them.
- Make every reported prompt compliance rate use one confidence-qualified calculation, so a later report compares the
  same measure rather than a favorable sample percentage.

Report concise progress at phase boundaries. Final output names the changed owner, passed and failed gates, evidence
locations, and any blocker with its required authority or input. Do not present a build, source inspection, or partial
test as proof of a broader delivered behavior.

List every denied, rejected, timed-out, or otherwise result-less requested check as unrun. Do not say that such a
command passed merely because it was requested, appears in a tool call, or another observation suggests the expected
result. Name only the check that actually produced its recorded result as passed.

For a variable prompt evaluation, report the one-sided 95% sequential lower confidence bound calculated from every
completed whole-prompt trial as `Compliance rate: <percentage> (95% confidence lower bound)`, such as `Compliance
rate: 71.9% (95% confidence lower bound)`. Report the persisted run record, test-suite, execution-context, and
execution-identity fingerprints, harness and profile, completed whole-suite pass and failure
counts, unrun or blocked count, and `PASS`, `FAIL`, `RUNNING`, or `INCONCLUSIVE` decision. Do not call the
observed pass fraction a compliance rate; if useful, report it separately as supporting evidence. When comparing
revisions, name both records and state that their delivered candidate-bundle, population, execution context, execution
identity, harness, profile, confidence level, and bound method match. Otherwise state that the rates are separate
series and do not compare them. Matching conditions make repeated measurements reproducible, but independent model
trials can still yield different numerical lower bounds; describe that difference as stochastic variation, not a
prompt change.

For a benchmark, also report each measured prompt file's elapsed wall-clock duration from evaluator start through its
terminal result beside its candidate-bundle identity, trial count, and compliance rate. Do not substitute one paired
benchmark's total duration for either prompt file's own duration.

For a direct-route diagnostic, report the retained certification case that needed the investigation, whether the rule
was delivered, and the observed task result. Call it a diagnostic result, not a compliance rate, pass rate, or prompt
certification.
