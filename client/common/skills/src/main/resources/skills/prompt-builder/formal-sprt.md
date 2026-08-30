# Formal Prompt Evaluation

## Design Goals

- Establish prompt compliance from a stable, versioned test suite and independent trial results rather than one
  favorable execution.
- Permit comparison of prompt revisions only when each evaluated candidate is proved delivered and every
  non-candidate part of one fixed comparison contract is identical.
- Certify a prompt only when complete-suite evidence establishes at least 95% compliance at 95% confidence, while using
  SPRT to stop a clearly weak candidate early.
- Make compliance results comparable across prompt revisions by reporting one unchanged, confidence-qualified measure
  rather than a sample-specific pass percentage.
- Minimize evaluation wall-time by executing independent test and whole-prompt trials concurrently without losing or
  mixing their retained evidence.
- Let a router revision become an evaluation baseline only after every relevant organic route still selects or excludes
  the required guidance.
- Treat a failed executor, validator, runner, sandbox, cache, or evidence operation as an infrastructure failure, not
  as evidence about prompt compliance, so a benchmark stops before it mixes invalid work with valid trials.
- When an evaluation stops before it records a result, preserve a single safe transition to a terminal infrastructure
  result instead of mixing its incomplete work with a later evaluation.
- Keep a retained evaluation record self-verifying after handoff, so later reporting and comparison use its final
  evidence rather than an obsolete pre-handoff copy.

## Guidance

Use the decision-kind, decision-maker, and delivery-form vocabulary in
[evaluation-terminology.md](evaluation-terminology.md) when defining a suite, a check, a model review, or a result
record.

Keep each prompt's test suite in version control, outside the delivered prompt artifact. Under the owning module's test
resource root, mirror the tested prompt's path relative to its production resource root. Each stable scenario owns
`<scenario-id>/scenario.md` and may own a sibling `project/` fixture. The evaluator creates one fresh Runner workspace
for that scenario, copies `project/` when it exists, and gives the Runner only `## Scenario`; the evaluator keeps
`## Assertions` and every validator receipt. Assertions are non-empty, uniquely named propositions about retained
execution evidence. In an organic expectation,
`selected` names routes every compliant response must load; `optional_selected` names routes a compliant response may
load because its own trigger can become true in one permitted response; and `not_selected` names strict exclusions.
When a historical candidate moved one or more routed files but the testcase's task remains the same, materialize the
bundle with one `--route-replacement <ordinary-route> <candidate-route>` for each move. The candidate benchmark
substitutes only those declared route paths and proves the historical router owns every resulting route. A normal
evaluation without a candidate bundle continues to use the suite's declared paths. Do not use a route replacement to
change task inputs, companions, exclusions, or a candidate's measured behavior; it only preserves organic routing
across canonical prompt-file moves.
The batch command copies
each fixture into an initially absent temporary workspace. After a retained organic failure, first run
`prompt-evaluation classify-failure <source-run.json> <case-id> <trial>`. When its retained evidence identifies an
owner, repair that owner instead of launching another model trial. Only an `unresolved` result may create the one
diagnostic that answers its remaining question. `prompt-evaluation routing-diagnostic <source-run.json> <case-id>
<trial> <prompt-artifact> <harness> <execution-context> <runner> <build-plan.json>
<retained-directory-under-cat-work-temp> [--max-concurrency <count>]` creates and runs a routing-only matrix from the
failed run's retained certification-suite snapshot: it preserves every organically routed task and fixture, stops after
selection, measures `organic_selection`, and cannot produce a compliance rate or comparison. `prompt-evaluation
create-direct-diagnostic <source-run.json> <case-id> <trial> <diagnostic-suite-directory>` creates an application-only suite
from the failed run's retained certification-suite snapshot: it directly delivers the expected route and measures
`delivery` plus `application`. Generate either suite below
`${CAT_WORK}/temp`; do not store a duplicate model-test suite. When an organic routing failure leads to a router edit,
run the complete routing diagnostic before promoting that router revision; every matrix case must pass. A prompt or
implementation edit that does not change the router needs no routing-matrix promotion gate. Then publish the returned
routing-matrix run with `prompt-evaluation promote-router <source-run.json> <routing-matrix-run.json>
<rules-root> <router-promotion.json>`. The second command refuses a failed, incomplete, or altered matrix and records
the originating organic failure, the matrix run, and the current router hash. Before a conditionally routed evaluation,
run `prompt-evaluation audit-routing <rules-root> <router-rule> <certification-suite-directory>` to verify that the router
owns every asserted selected or excluded route and that its cases measure organic selection plus application.

When [model-effort-policy.md](model-effort-policy.md)'s capability-triage gate is satisfied, run its generated
one-case diagnostic with `prompt-evaluation capability-diagnostic <source-run.json> <case-id> <trial>
<diagnostic-suite-directory> <prompt-artifact> <harness> <execution-context> <runner> <build-plan.json> <executor-model>
<executor-effort> <retained-directory-under-cat-work-temp> [--max-concurrency <count>]`. The command verifies the
localized repeated failure and the retained target-profile revision history, keeps the ordinary validator profile,
changes
only the executor profile named by the command, and runs exactly three matched trials. Its report records both executor
profiles and the fixed validator profile. It is non-certifying and cannot contribute a compliance rate or revision
comparison.

The manifest order is the stable checking order. Put the testcase most likely to distinguish the required behavior from
the nearest wrong behavior first, then retain that order across prompt revisions. The evaluator fills shared capacity
with complete trials in that order: each active trial starts its next testcase before another trial repeats the
first
one. It independently reviews each testcase's retained evidence across the active trials. A deterministic failed
testcase ends only that trial, so its remaining tests are not run; a model-reviewed failure ends later work only when
the
whole-suite certification decision becomes terminal. The manifest fingerprint includes this order, so changing it starts
a new comparison series.

The manifest must use these fixed SPRT settings:

- `accept_pass_rate = 95%`: treat true whole-prompt compliance of 95% or higher as acceptable.
- `reject_pass_rate = 85%`: treat true whole-prompt compliance of 85% or lower as unacceptable.
- `false_accept_rate = 5%`: if true compliance is 85% or lower, allow at most a 5% chance that observed passes make
  SPRT accept it.
- `false_reject_rate = 5%`: if true compliance is 95% or higher, allow at most a 5% chance that observed failures make
  SPRT reject it.

Do not revise a suite merely to make a changed prompt pass; create a new suite revision only when its required behavior
changes, and treat it as a new comparison baseline.

Before creating or changing the selected evaluation build plan, read
[build-plan-provenance.md](build-plan-provenance.md). That companion defines which project artifacts may be declared as
the plan's unchanged inputs; this evaluation procedure consumes the resulting plan.

To measure a pre-change baseline or another artifact without changing candidate state, use
`prompt-evaluation benchmark <scenario-suite-directory> <candidate-bundle> <harness> <execution-context> <runner>
<build-plan.json> <retained-directory-under-cat-work-temp> [--max-concurrency <count>]`. It runs the same
preflight, packaged-runner lifecycle check, immutable-input preparation, and terminal statistical procedure as formal
evaluation, but records `mode: benchmark` and never promotes, clears obligations, or changes a candidate-state file.
Before starting a benchmark, use `prompt-evaluation materialize-candidate <source-rules-directory>
<bundle-directory-under-cat-work> <primary-rule> [<additional-rule>...] [--route-replacement <ordinary-route>
<candidate-route>]...` to copy the measured prompt and every routed
companion it needs from their canonical installed paths into one immutable candidate bundle. The prompt selects the
historical source tree and canonical rule paths; the CLI validates and copies them. The runner must receive that bundle,
not the current packaged rules plus a detached prompt file recorded only as metadata. Reject the run before a model turn
unless its route receipt proves that the declared canonical paths delivered the bundle's exact hashes. A historical
baseline uses `materialize-candidate-git <repository> <commit> <repository-relative-rules-root>
<bundle-directory-under-cat-work> <primary-rule> [<additional-rule>...] [--route-replacement <ordinary-route>
<candidate-route>]...`; it reconstructs its historical router, root prompt, and companions from that immutable commit.
A diagnostic that directly injects one file may help isolate
application behavior, but it is not an end-to-end benchmark and must not report a comparison result.

For one comparison, fix the population and case weighting, validator contract and statistic, harness, executor and
validator profiles, runner runtime, and semantic sandbox, network, and cache policies. Candidate-bundle hashes are the
only intended differing input. Record physical artifact locations as provenance, outside the comparison identity, so
equivalent copies remain comparable. A benchmark is compliance evidence for its delivered candidate bundle; it is not a
candidate promotion or certification decision.

Use `prompt-evaluation benchmark-pair` for an old and new candidate. It validates both bundles, pins the comparison
contract, interleaves an equal fixed number of whole-suite trials using identical generated inputs, and stops early only
for an infrastructure failure. Certification may still stop once its confidence decision is terminal. Do not compare
adaptive trial counts as an improvement measurement: a later candidate can otherwise receive a different amount of
evidence merely because it failed or passed sooner.

For every benchmarked prompt file, retain and report its elapsed wall-clock duration from evaluator start through the
terminal result, beside its candidate-bundle identity, trial count, and confidence-qualified compliance rate. This
separates a prompt-quality change from a change in the cost of measuring it.

Certification and benchmark must select the same frozen population and use the same compliance statistic when they
measure the same delivered candidate without an intervening prompt revision. The two model-run samples can produce
different lower bounds, so do not require matching percentages. Instead, require the same candidate-bundle identity,
population and weighting, comparison-contract identity, and confidence-bound method; retain both sample counts and
report any remaining difference as stochastic variation rather than a prompt regression. Select a new frozen population
only after the candidate changes; do not replace certification's population with a later expanded visible suite merely
because another scenario was added after the earlier certification began.

Before an all-case regression check or formal trials after a revision, run `prompt-evaluation smoke-evaluate <scenario-suite-directory>
<prompt-artifact> <harness> <execution-context> <runner> <build-plan.json> <case-id>
<retained-directory-under-cat-work-temp> [--max-concurrency <count>]` with the testcase most likely to expose the
changed decision. This one-case run is diagnostic only; it does not contribute to certification or promotion. If it
fails, retain and classify that evidence, repair its identified owner, and do not spend a wider all-case check or formal
trial on the candidate.

When that passed smoke uses a replacement execution identity for the unchanged unfinished candidate, run
`prompt-evaluation revalidate-runtime <candidate-state.json> <passed-smoke-run.json>` before regression. It records the
specific smoke receipt as the authorization for that replacement runtime; it does not promote the candidate or certify
compliance. If the command rejects the state or smoke receipt, follow its diagnostic rather than changing execution
conditions again.

After that smoke passes, run `prompt-evaluation regression-check <smoke-run.json> <candidate-state.json> <scenario-suite-directory>
<prompt-artifact> <harness> <execution-context> <runner> <build-plan.json>
<retained-directory-under-cat-work-temp> [--max-concurrency <count>]`. The CLI rejects a smoke receipt unless its
successful case and every candidate and execution identity field match the pending all-case check. The assembled
prompt-route hash together with the evaluation epoch identifies a candidate: it includes the
prompt and every delivered companion, whereas an individual source-file hash is provenance only. The candidate state
retains that identity with a completed check's stable prompt-failure signatures and the last promoted candidate. When
every case passes, the command promotes that exact candidate before returning; a failed check records its prompt
failures, while an incomplete check records its infrastructure result without changing candidate state. Both leave the
prior promotion unchanged. A passing finite check records each earlier failure as not observed in its comparable exposures;
it does not remove that failure from later regression checks. The CLI exercises all active failure cases concurrently
under the shared limit: the first observation requires at least 8 stress trials, its first recurrence requires at least
16, and later recurrences require at least 32. It restarts that coverage when the assembled prompt route or evaluation
epoch changes, because evidence from a different delivered prompt, suite, profile, or execution policy is not
comparable.
Before that signature-specific work, the check runs every durable suite case once. That one non-certifying baseline can
expose a defect in a case unrelated to the most recent failure before a longer formal run starts without duplicating the
statistical work certification performs. A broader all-case sweep is a diagnostic after retained evidence identifies a
need for it; it is not a promotion prerequisite. These trials remain outside the formal compliance ledger.
Only a matching terminal certification PASS clears active failures. Then select the version-controlled build plan for
the chosen harness and run `prompt-evaluation certify-promoted <candidate-state.json>
<scenario-suite-directory> <prompt-artifact> <harness> <execution-context> <runner> <build-plan.json>
<retained-directory-under-cat-work-temp> [--max-concurrency <count>]`. Before it builds the runner or starts a model
turn,
the command runs every declared deterministic-check known-good and known-bad fixture and stops when one disagrees
with its expected result. It then runs the selected plan's explicit shell-free build command, checks that it produced
the declared runtime artifact, asks the runner to verify that artifact, and records its content fingerprint before
creating any trial. Do not reconstruct the build command, its
working directory, or the runtime path in prompt prose. Invoke this workflow through the installed plugin's CLI, not
through an image that the selected build plan replaces. A compiled source class or an earlier build does not prove that
the runner invoked by a new trial contains the current source. The evaluation command validates the suite, resolves the
runner's default executor and validator profile, verifies the shared writable dependency cache, creates the fresh
evaluator-owned workspace, snapshots execution-policy artifacts, generates the execution plan, starts the run record,
and runs it to a terminal decision. Do not recreate any of those paths, profile fields, policy files, runner arguments,
plan JSON, batch, or trial coordination in prompt prose. Use the active harness's `workflow-temp create` launcher to
allocate a fresh invocation directory below `${CAT_WORK}/temp`, then pass a fresh, initially absent child of that
directory to every evaluation command; the evaluator creates and owns that child, and rejects an existing one so
earlier evidence remains available for comparison. Do not reconstruct the CAT work root or use `/tmp`, a default
system temporary directory, or another shared location. The
version-controlled execution-context file identifies the validator instruction, available context, and other conditions
that affect execution.

Each selected build plan declares a nonempty `inputs` array of the relative files or directories that can change its
artifact. The evaluator fingerprints those inputs, the plan, the runner, and the verified artifact. It can reuse a
bounded assembly receipt retained below `${CAT_WORK}/temp` only when all four still match; a missing declaration,
changed input, missing artifact, or failed runner verification rebuilds. Do not describe a command or working
directory as proof of unchanged
inputs: the declared-input fingerprint is the proof.

The command retains a successful deterministic-check preflight receipt below `${CAT_WORK}/temp` and reuses it only
when the suite fingerprint is unchanged. That fingerprint includes each testcase fixture and prompt, the check program,
and every known-good or known-bad check-control project. A changed input produces a new receipt and reruns preflight;
do not bypass a failed preflight or treat an unrelated earlier receipt as proof that the current check works.

A deterministic check may use `${fixture}`, `${check}`, `${project}`, `${executor_result}`, and `${output}` in its
shell-free command arguments. `${executor_result}` is the executor's retained output, so the evaluator invokes that
check after the executor finishes and before it asks the model reviewer for a verdict. A self-test for such a check
must declare an `executor_result` fixture alongside its control project; the evaluator runs those controls during
preflight. This lets event order, file declarations, and other recorded facts receive an exact check without asking a
model to reconstruct them from a report.

When a deterministic check must install a dependency, generate a probe artifact, or otherwise write while verifying
the executor's output, set its `"writable_project":true`. The evaluator copies the retained project and output into an
isolated writable verifier project, runs the check there, and retains its result before model review. Its known-good and
known-bad controls receive the same copying treatment. Do not write into the executor project: later checks need it as
unchanged evidence of what the executor produced.

Start this long-running command through the available PTY-capable tool interface, retain its returned session identifier
immediately, and poll that exact session until it returns a terminal exit result; a yielded poll is not a completed
evaluation. Do not end the prompt-building task, report the evaluator unavailable, or launch a background substitute
while that session remains live. A documented durable-job service may replace the open session only when its job
identifier and terminal status are retained in the run record. When an observation is needed while the PTY remains
live, use `prompt-evaluation status <retained-directory-under-cat-work-temp>/run.json --summary`. The active evaluator
atomically publishes `current_phase`, `active_workers`, and an increasing `status_revision`; `owner_active: true` and
`next_action: "wait_for_terminal_result"` mean the evaluator still owns the work, even when no new decision has yet
appeared. Keep waiting on the retained PTY session in that state. Do not infer a stall from an unchanged trial number,
an incomplete directory count, a previous stage's receipt, or a truncated full status response, and do not interrupt
the evaluator while its published owner is active. If an evaluator has ended but `next_action` is
`wait_for_orphaned_workers`, a runner still owns a worker; wait for it instead of terminating work that may complete.
If the evaluator ended with ownerless workers, or no worker remains, run
`prompt-evaluation recover <run.json> <workspace-root>` when status directs `next_action: "recover_interruption"`.
Recovery first records only ownerless workers as interrupted; it refuses to disturb a runner that still owns work. Its
`recovery_mode` tells the caller what it will do: with
`resume_incomplete_batch`, it uses the retained plan and recorded worker capacity to repeat only the claimed testcases
that lack a terminal result; with `record_infrastructure_interruption`, it records `INFRASTRUCTURE_FAILED` because no
retained batch can be completed safely. Do not invoke `run-until-terminal` directly or reconstruct its arguments after
an owner disappears. Follow `next_action` and `recovery_mode` rather than choosing termination, resumption, or cleanup
from a caller-side scan. `current_phase_elapsed_millis` shows how long the active
phase has run, and `completed_phase_durations_millis` shows the time already spent in earlier phases. Use those
fields
to identify a slow build, harness turn, or validator before changing concurrency or the prompt. The command creates
each fresh batch workspace, atomically claims as many complete
whole-prompt trials as the requested shared worker capacity allows, copies every testcase fixture, starts and waits for
each executor, prepares validator evidence from every executor's retained project, starts and waits for one independent
validator per testcase phase, and records a terminal result for every testcase from its verdict map. It stops scheduling
later phases as soon as it records `PASS`, `FAIL`, or `INCONCLUSIVE`. It records `blocked` with retained diagnostics if
either command fails. Do not manually coordinate batches, continuation, or testcase results.

After `certify-promoted` records a terminal decision, use `prompt-evaluation report
<retained-directory-under-cat-work-temp>/run.json` for the standard compliance result. Use `prompt-evaluation finalize
<run.json> <workspace-root> delete` only when no
later comparison, report, or retry needs the record or retained artifacts. Otherwise use
`prompt-evaluation finalize <run.json> <workspace-root> retain <retained-evidence-directory-under-cat-work>` so the
evaluator verifies every recorded result, stores those results by content hash, rewrites the retained record, and then
removes the terminal workspace and isolated harness runtime. Record the returned location and byte-count receipt. Delete
the outer invocation wrapper as part of that finalization when the record occupied only one of its children. The receipt
states whether the wrapper moved with the evaluation or was deleted after evidence handoff. Delete the retained record
and its content-addressed evidence only after its last recorded consumer finishes; an unrun or live evaluation remains
owned until its recorded terminal cleanup.

When a retained FAIL is corrected by a later retained PASS, invoke `prompt-evaluation link-correction
<failed-workflow-journal.json> <correction-workflow-journal.json>`. The command changes the failed journal from
`current` to `superseded` and records the exact retained PASS journal that replaced it. It refuses a live, non-PASS, or
already-superseded record, so a failed retry remains its own current diagnosis instead of being mistaken for a
correction. Delete the superseded evidence only after that link and its last recorded consumer are complete.

`evaluate` owns the execution-plan schema and runner arguments. Its generated plan runs commands directly, not through a
shell, excludes personal user rules from CAT-installed executors, and gives a validator only the completed executor
project and result it must inspect. The coordinator fingerprints the generated commands and execution-policy artifacts
before it claims a trial, verifies them at a terminal decision or comparison, and rejects a comparison whose execution
identity differs. It sends evaluator-owned JSONL turns, retains runner receipts, and reads the complete validator verdict map. A
batch runs independent workers concurrently up to its requested limit, which may lower but cannot exceed CAT's shared
cross-harness limit of 16 native instances. A complete-suite trial passes only when every test passes. The command
fingerprints the manifest, declared prompts, declared fixtures, execution plan, and execution identity. It records the
raw-result and validator-result hash when each attempt is appended, then verifies all retained attempt hashes only when
a terminal decision, comparison, or durable handoff needs the complete evidence.

For a testcase that declares later `turns`, the evaluator expands its initial `prompt` into the first `turn` and sends
each declared `steer`, `interrupt`, or follow-up `turn` through that same bridge before `complete`. A mid-turn control
must declare `after: "turn-started"`, so the bridge dispatches it only after submitting the initial turn. A follow-up
turn is sent after the preceding turn reaches its terminal event. The runner, not a test shell script, owns the live
session and these ordering guarantees.

When reviewing a testcase that requires an exact routed artifact, use the retained event order to decide whether that
artifact was successfully obtained before the first task-bearing action. Do not reject a result solely because an
earlier alternate lookup failed. Treat that lookup as retained performance evidence unless the testcase explicitly makes
the lookup's cost, authorization, safety, or result observable.

At evaluation start, the run record snapshots the declared suite, prompt artifact, and execution context beside itself;
every trial reads those snapshots, never their mutable source paths. Its sibling `.attempts.jsonl` ledger appends one
complete hashed attempt at a time. Do not rewrite prior ledger records during evaluation. An evaluator-owned handoff may
atomically rewrite the ledger only to replace moved evidence paths after it has verified every prior hash.

The batch prepares named copied validator evidence only when its plan does not declare read-only evidence support. A
direct-evidence validator receives the executor run's retained `project` directory and raw result, never the unchanged
trial fixture. A copied-evidence validator copies inspectable source evidence and the raw result into an initially
absent workspace while excluding Git metadata and generated dependency installations that cannot be safely copied into
another harness. Exclude only the plan-declared generated directories; retain all source, configuration, and raw-result
evidence. A validator that received the initial fixture or returns an incomplete verdict map cannot establish the
executor's result; the batch records the affected attempts as blocked rather than passing them.

The evaluator updates the decision after every completed batch. It maintains a sequential lower confidence bound
from the same whole-prompt results. `PASS` means the lower bound reached 95% at 95% confidence. `FAIL` means SPRT
established the weak-candidate side; revise the prompt and start a new record. A passing SPRT result alone is not
certification. After 100 complete suite trials without either result, it records `INCONCLUSIVE`.

If any executor, validator, runner, sandbox, dependency-cache, evidence, or other benchmark operation fails to produce
the required terminal evidence, stop the benchmark immediately and record that infrastructure failure with its retained
diagnostic. Do not schedule another testcase or trial, use partial outcomes as a compliance measurement, retry inside
the same record, or tune the prompt in response to it. Repair the failed infrastructure first. Then rebuild the affected
runtime when applicable, create a new evaluation workspace and result record, and restart the benchmark from its first
trial. A prompt behavior verdict is different: a completed independently validated failing testcase is valid `FAIL`
evidence and follows the normal prompt-revision path.

For every run, calculate `compliance_rate` as the one-sided 95% sequential lower confidence bound from all
completed whole-prompt trials. In user-facing prose, display it as `Compliance rate: <percentage> (95% confidence
lower bound)`, such as `Compliance rate: 71.9% (95% confidence lower bound)`. This is the only value called the
prompt's compliance rate in a baseline, comparison, or trend. The observed pass fraction may appear as supporting
trial evidence, labeled
`observed_pass_fraction`; it must never replace, qualify, or be compared as the compliance rate. Preserve the
confidence level, bound method, completed-trial count, candidate-bundle fingerprint, comparison-contract fingerprint,
suite fingerprint, execution-context fingerprint, semantic execution-identity fingerprint, harness, and profile with
every rate. Keep that definition unchanged across versions: a new population, context, execution identity, harness,
profile, confidence level, or bound method starts a separate comparison series rather than an apples-to-apples rate
comparison.
Report complete-suite passes, total trials, the displayed compliance rate, and decision; do not substitute an observed
pass rate for certification.

To compare a revision with its pre-change baseline, use the paired benchmark record. Its report must show each
candidate-bundle identity, complete-suite count, and `Compliance rate: X% (95% confidence lower bound)`, followed by
the shared comparison-contract identity. Reject a comparison if either candidate was not delivered, if any non-candidate
contract field differs, or if its retained record no longer validates after handoff. A diagnostic comparison may list
different fields, but it must not call the runs comparable or report a before-and-after change. A prior run can
establish a pre-optimization baseline only when its record preserves a delivered candidate bundle and the same
comparison contract. Repeat measurements of one unchanged candidate are reproducible only when those same identities
match; their numerical bounds remain separate stochastic samples, not evidence of an improvement or regression.
