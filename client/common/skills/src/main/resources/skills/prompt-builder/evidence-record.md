# Prompt Evidence Record

## Design Goals

- Let a later reviewer reproduce a prompt failure or acceptance decision from recorded inputs and observable results.
- Preserve the test-suite and execution identity required to compare prompt revisions without mixing conditions.

For each run, record `prompt_revision`, `task_input`, `harness`, `profile`, `allowed_context`, `tool_events`,
`raw_result`, `validator_result`, and `status` (`passed`, `failed`, `unrun`, or `blocked`). For a failure also record
`failure_class`, `owner`, `sibling_enumeration`, `invariant`, and `next_action`. Store the record with the test evidence
or another durable project artifact when a later run must consume it; otherwise recreate it for the current run. Before
preserving a record, run the current harness's `prompt-evidence-check` launcher on it; it accepts a complete record and
rejects an incomplete one.

When `suite.json` maps an assertion ID through `assertion_obligation_families`, record that family and enumerate every active failed scenario and assertion
in it. A recurrence review must use that enumeration rather than treating one testcase's failure as evidence that the
other testcase failures were considered.

When a retained obligation exists and a prompt revision changes, record an independent revision review for that exact
candidate identity before its next model run. The record enumerates every active and recently covered obligation and,
for each one, the actor, direction, affected scope, correction target, and verification outcome preserved by the
revision. A review for an earlier candidate identity is evidence about that earlier text, not authorization to run the
changed candidate.

Record a direct-route investigation as `purpose: diagnostic`, linked to the retained certification record and case that
made routing ambiguous. Its result establishes only whether an explicitly delivered rule changes the observed task
behavior. Do not add its trial to a compliance rate, trend, or before/after comparison.

For a variable executor, also retain the `prompt-evaluation` run record. It names the suite and prompt fingerprints,
harness, profile, every whole-prompt trial's test outcomes, and its raw and validator result locations. Keep `unrun`
and `blocked` attempts distinct from completed failures.

Preserve `compliance_rate`, the one-sided 95% sequential lower confidence bound calculated from completed whole-prompt
trials. Render it to a user as `Compliance rate: <percentage> (95% confidence lower bound)`, such as `Compliance
rate: 71.9% (95% confidence lower bound)`. The record must also preserve:

- The confidence level, bound method, and completed-trial count.
- The suite, execution-context, and execution-identity fingerprints.
- The harness, profile, and `PASS`, `FAIL`, `RUNNING`, or `INCONCLUSIVE` decision.

Retain the observed pass fraction only as separately labeled supporting evidence. A baseline, trend, or comparison claim
must use compliance rate and name paired run records whose suite, execution context, execution identity, harness,
profile, confidence level, and bound method match. When trials run concurrently, preserve the claimed trial-number
range and every completed worker result; a later terminal decision must not erase work that already completed.

After a runner receipt verifies its retained project and raw response, retain the project, runner manifest, one native
event stream, receipt, command output, validator verdict, and execution identity. Do not retain a second terminal
field that repeats the full native event stream. Remove the isolated harness home,
marketplace copy, and other runtime cache files: they are reproducible setup, not evidence of the executed behavior.
