# Tests

## Design Goals

- Produce independent tests that exercise prompt application, and organic selection only when the test leaves routing
  to the executor, without revealing the expected answer to the executor.
- Use test results to distinguish a routing failure, an instruction-application failure, and an unavailable
  execution prerequisite.
- Match each assertion to the product its prompt obligation governs, so a behavior test does not fail on harmless
  exploratory language and an output-language test does not overlook required wording.
- Preserve each test's task input, initial project fixture, and assertions across revisions so a later evaluation
  can measure a changed prompt against its pre-change baseline.

## Guidance

When designing or reviewing a prompt test's task, fixture, routing, or assertion placement, lazy load
`rules/include/common/organic-prompt-tests.md`.

For each obligation, write a task-realistic scenario with only the ordinary request, inputs, allowed context, and
observable acceptance evidence. Compare the ordinary scenario with a non-applying or edge scenario, an analogous
scenario, and the nearest dissimilar scenario that would detect an overbroad or incomplete route. The Design-Goal audit
rather than a reusable bucket establishes that coverage. Create certification scenarios first: they exercise ordinary routing and
application in the same task, so they do not pay for duplicate model trials. Use a generated diagnostic only after
`prompt-evaluation classify-failure` reports that retained certification evidence is `unresolved`. If it identifies
routing/delivery, application, a deterministic tool, an external prerequisite, or infrastructure, repair that owner
without a model diagnostic. An unresolved selection question creates and runs a routing-only diagnostic matrix through
`prompt-evaluation routing-diagnostic <source-run.json> <case-id> <trial> <prompt-artifact> <harness>
<execution-context> <runner> <build-plan.json> <retained-directory-under-cat-work-temp>
[--max-concurrency <count>]`; it recreates the suite from the failed run's retained certification-suite snapshot, uses
every unchanged ordinary task and fixture, stops after rule selection, and measures only organic selection. An
unresolved application question creates an
application-only diagnostic through `prompt-evaluation create-direct-diagnostic <source-run.json> <case-id> <trial>
<diagnostic-suite-directory>`; it recreates the case from the failed run's retained certification-suite snapshot, delivers
the expected rules, and measures delivery plus application. Both are diagnostic
evidence, not compliance evidence. Generate them under `${CAT_WORK}/temp`; do not persist a duplicate routing or
direct-route suite.

Do not use a profile-capability diagnostic to answer an ordinary routing or application failure. It is available only
after [model-effort-policy.md](model-effort-policy.md)'s repeated-failure gate is met. Reuse the generated routing-only
or application-only suite that isolates the repeated decision; the evaluator changes only its executor profile and
retains the ordinary validator profile for three matched trials.

Derive a diagnostic by retaining only fields that establish its bounded question. Remove an assertion, deterministic
check, validator setup, or prerequisite that owns an excluded product before validating the generated manifest. For
example, a routing-only case must not retain an implementation dependency check, because its executor stops before
implementation. A generated manifest that still requires excluded work is invalid diagnostic evidence.

First identify the product the obligation governs. A behavior-control obligation protects an operation, state change,
selected artifact, structured field, path, or other observable result. Assert that result and the event order or changed
artifacts that establish it. Do not fail that test only because an exploratory label, plan, or explanation uses a term
that could also describe a later final state. An output-control obligation protects reader-visible language itself, such
as a required response, diagnostic, comment, label, or documentation form. For that kind of obligation, the wording is
the behavior: assert the required language directly and state in the test why the reader needs it. A test that only
proves the skill was selected is incomplete; add a second assertion that would fail if the selected skill skipped its
required procedure.
Before asserting that the executor selects one branch, establish that the task contract makes that branch required by
the prompt. Otherwise assert the comparison or evidence the prompt requires, not an option that a different compliant
branch could reject. For example, require an established dependency only when the test contract makes an established
dependency suitable; do not require one for an unbounded exact-arithmetic contract if every available dependency has a
conflicting precision or range limit.

When a routed prerequisite names an exact artifact, assert its successful use before the first task-bearing action, not
that the executor performed no unsuccessful exploratory lookup. Treat a failed alternate lookup as a performance
observation unless it prevents the canonical prerequisite from being obtained in time or itself changes an observable
result, authorization, cost, or safety boundary. Keep the raw event order so a validator can distinguish that resolved
overhead from an unmet prerequisite.

Keep the assertion outside the executor's prompt. A durable test contains its task prompt, every initial project file
the executor needs, and its assertions. Keep its initial project fixture under the version-controlled test suite; do not
create or revise it ad hoc in a temporary trial workspace. Before each execution, use the test tooling to copy that
fixture into a fresh temporary workspace. For a CAT workflow, use the active harness's `workflow-temp create`
launcher so that workspace is below `${CAT_WORK}/temp`; do not place it in `/tmp`, a default system temporary
directory, or another shared location. The receiving evaluation CLI may instead create and own a fresh, initially
absent child of
that invocation directory. Preserve the rendered instructions, inputs, tool events, output, and validation result.
When a result fails, first establish whether the target was delivered, then whether its required action occurred, and
finally whether an external prerequisite blocked the action. Do not infer private reasoning from the final output.

For a scenario suite, separate the prompt's reusable contract from a fixture detail that makes one testcase
concrete. Record each fixture-only domain name, value, or phrase in `generalization.fixture_distinctives` with its
owning testcase. Do not put a contract term there merely because the fixture uses it. When a reader-facing prompt must
repeat a fixture-only phrase because an external contract requires it, record that contract as the distinctive's
`external_authority.path`; otherwise teach the same decision with a dissimilar example. The validation workflow audits
these declarations before any variable trial.

Store every durable scenario in the version-controlled suite required by `formal-sprt.md`. Under the owning module's
test resource root, mirror the tested prompt's path relative to its production resource root. Each stable scenario ID
owns `<scenario-id>/scenario.md` and may own `<scenario-id>/project/`. `scenario.md` contains `## Scenario` for the
Runner and `## Assertions` for the evaluator. Each nested assertion has a stable ID and directly states a proposition
about retained execution evidence; it does not ask the Runner a question or say “Pass only if”. Keep the scenario,
optional project fixture, and assertions unchanged while comparing prompt revisions. Add or revise a scenario only
when the prompt's required observable behavior changes; record the resulting suite revision instead of combining its
results with an earlier suite.

For an organic route whose task can produce more than one compliant response, put only the always-required paths in
`selected`; list a route in `optional_selected` only when its trigger can become true in a permitted response and its
delivery cannot invalidate the task. `not_selected` remains a strict exclusion. Do not label a route optional merely to
accept an unexplained over-route. For every conditionally routed certification suite, name its owning router and run
`prompt-evaluation audit-routing
<rules-root> <router-rule> <certification-suite-directory>` before variable trials. The deterministic audit must establish
that the router references every expected selected or excluded route and that the suite measures selection plus
application. It does not certify model behavior: preserve the organic run for that evidence.

When a prompt obligation concerns steering, interruption, or resumption, declare its deterministic interaction in the
test case's optional `turns` array. The required top-level `prompt` remains the initial `turn`. Each later object has a
`type` of `steer`, `interrupt`, or `turn`. A `steer` names a version-controlled prompt file and an `after` value of
`turn-started`; an `interrupt` has the same `after` value and no prompt; a later `turn` names the version-controlled
prompt file that resumes work and has no `after`. The evaluator sends those JSONL bridge messages in their declared
order, then sends `complete`. This lets the test assert both the mid-turn control and the later observable outcome
without relying on timing, an ad-hoc shell process, or a separate runner mode.
