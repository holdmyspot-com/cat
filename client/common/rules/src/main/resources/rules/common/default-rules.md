---
depends-on:
  - ./design-quality.md
  - ./backward-design.md
  - ./user-request-scheduling.md
agents: ["main"]
---
# Default Rule Routing

## Design Goals

- Provide the foundational Design Quality and Backward Design guidance before a reader makes an implementation or
  review decision, then route specialized decisions to their focused rule.
- Route an input-preservation decision from the stated contract before project inspection or candidate selection can
  commit to a transformation that might discard the needed distinction.
- Make each required route action derive the installed rule location and load its selected rule in the same shell
  command.

## Guidance

Before choosing a task route, classify the requested action rather than matching a word. When the task only corrects,
documents, renames, or repeats existing text while preserving its meaning, choose only routes that govern editing that
text; do not read a subject-matter rule because the text contains its terminology. For example, correcting a term in a
document does not ask the reader to implement, select, validate, or otherwise decide anything about that term. A word
in quoted text, an example, a filename, or existing material is not a request to make the decision a route governs.
When the task does ask for that decision, choose the route from the decision itself.

When a task requests parsing, serializing, validating, or otherwise implementing a named standardized format or another
externally defined capability, choose its complete initial route from the stated task contract before any project
inspection, candidate search, probe, or implementation. This trigger includes a standard-format parser or serializer,
protocol handling, cryptography, scheduling, and decimal arithmetic. Derive `CAT_PLUGIN_ROOT` in that same shell action
as the loaded harness Plugin Root guidance requires.

For a task that met the standardized-capability trigger above, choose its route as follows. If it accepts one written
form and rejects another because of how it is written, rather than only because of the value ordinary processing
returns, first run the active harness's complete `CAT_PLUGIN_ROOT` derivation from its Skill Environment guidance, then
append this route read in the same shell action. The displayed read is the action's tail, not a standalone command. It
reads `prefer-reuse.md`, then `preserve-required-distinctions.md` in this exact order. This includes a canonical
spelling that must be accepted while another spelling of the same external identifier, timestamp, number, or
configuration value must be rejected; the task need not first prove that the chosen API will collapse the forms:

```
cat "$CAT_PLUGIN_ROOT/rules/include/common/prefer-reuse.md" && cat
"$CAT_PLUGIN_ROOT/rules/include/common/preserve-required-distinctions.md"
```

Otherwise, append only its first read after the same complete harness-specific derivation. This alternative applies only
after the standardized-capability trigger above is true and the contract permits the representations to be treated
alike. When that trigger is false, neither command applies. Reading only the first file leaves the paired-input route
incomplete; do not read another rule, inspect the project, or take any other task action after that prefix.

Treat the required harness-specific derivation and every displayed multi-file read tail in this routing file as one
indivisible route action: after choosing its condition, run the complete derivation, then the whole displayed tail
without splitting, shortening, or editing it. A successful prefix is neither a completed route nor authority to
discover its remainder; do not use it to infer, search for, or try another filename. Use the filenames in the displayed
tail as literal route targets. If any required read fails, stop and report the routing failure instead of inspecting the
project or choosing an alternative. The route, rather than the task's source language, topic, or a remembered rule
label, selects the required files. Do not reply `ROUTES READY` until that one route action has succeeded.

A displayed read whose tail expands `CAT_PLUGIN_ROOT` is not a standalone shell action. In that same action, first
derive and validate `CAT_PLUGIN_ROOT` with the active harness's Skill Environment guidance, then append the displayed
read as a later shell statement. Do not run its `cat` tail with an unset value: the shell then reads `/rules/...`
instead of CAT's installed rule. Likewise, do not write `CAT_PLUGIN_ROOT=... cat "$CAT_PLUGIN_ROOT/..."`: the shell
expands the argument before applying that command's temporary assignment, so it also reads `/rules/...`. Do not guess
an installation path after that failure; the harness-specific derivation identifies the installed artifact and detects
a conflicting local value. Treat the combined derivation and read as the indivisible route action, and proceed only
when it exits successfully.

An isolated CAT runner may instead deliver the complete text of selected rules in a dedicated pre-task turn that says it
activated those task-triggered rules. That runner delivery makes only those rules available for the later task; do not
read the delivered rules again. It is not global injection: rules without a true trigger remain absent. Without that
dedicated complete delivery, use the ordered reads above.

When that runner first asks for a machine-readable route selection, return only the selected canonical relative paths in
its required format. A path is below the installed `rules` directory, so it does not repeat the `rules/` prefix. Do not
perform the route reads in that selection turn: the runner validates the selected paths and delivers their exact
installed contents in the following pre-task turn. A malformed, unknown, or duplicate selection stops before the later
task, so it cannot substitute an informal acknowledgement for an activated route.

For each displayed routing command, form its selection value by copying the literal suffix after
`$CAT_PLUGIN_ROOT/rules/`. Preserve every directory separator and the filename exactly as displayed. Do not derive a
selection value from a rule's human-facing title or flatten a nested companion path into a hyphenated filename.

A task-specific response that states an intended inspection, plan, implementation, test, or conclusion is task-bearing
for this route. Before the required reads, do not announce such work; either perform the required reads first or give no
task-specific commentary. A generic progress acknowledgement that does not name or commit to a task action is allowed.

Outside the externally defined-capability route above, before selecting or changing a component, API, storage mapping,
or configuration that transforms input, successfully read `../include/common/preserve-required-distinctions.md` when
the contract requires an operation to make different decisions for two representations that the transformation could
collapse. This includes treating an absent configuration field differently from an explicit value, preserving identifier
case, and distinguishing an omitted timestamp offset from an explicit one. Do not load this rule when the contract
expressly permits the forms to be treated alike.

Before defining or changing a method that represents, accepts, returns, validates, converts, or calculates money—
including a price, charge, balance, fee, payment, tax, or discount—first lazy load `../include/common/money.md`. Do
not choose its representation, signature, or implementation until that rule is available.

When defining, changing, or auditing function and method names across a source language, lazy load
`../include/common/function-naming.md`.

When defining, changing, or auditing terminology used for a shared concept across source code, configuration,
documentation, commands, or rules, lazy load `../include/common/consistent-terminology.md`.

When defining, changing, or reviewing parameter validation for a caller-facing operation, lazy load
`../include/common/parameter-validation.md`.

When writing or editing maintained documentation, including product, legal, API, function, method, command,
configuration, or user-facing workflow documentation, lazy load `../include/documentation/general.md`.

Before writing or editing a reader-facing update whose new evidence requires an actor to correct, narrow, or otherwise
change an action, run this one shell action:

```
cat "$CAT_PLUGIN_ROOT/rules/include/common/write-clearly/unexpected-result.md"
```

This includes a failed test, review, or observed result that requires a corrective action even when the task never
states an earlier plan. Do this after the task gives the update's facts and before drafting it; do not load the
companion merely because a task mentions prose that the reader will not receive. This is the complete route for that
update: do not add a general reporting or failure-analysis rule merely because the update names a failure. Add a
failure-analysis route only when the task also asks to investigate its cause, determine why it occurred, or design its
prevention; otherwise the focused companion is the complete route. Do not replace it with the later general one-file
Clear Writing route.

Before writing or editing another user response, prompt or rule file, source comment or Javadoc, command help text, or
error message whose new evidence does not change the action the author planned to take, select its route after the task
gives the reader-facing content and before drafting it. If that reader-facing update reports a tool, skill, subagent,
command, test, or investigation as completed, failed, or unavailable, read `../include/common/write-clearly.md`, then
`../include/common/check-assumptions.md` in that order as one route. A task-supplied result is still a reported claim.
Otherwise read only `../include/common/write-clearly.md`. Do not load either route merely because a task mentions prose
that the reader will not receive.

When documenting Bash functions, Bats tests, or Bash source code, lazy load `../include/bash/documentation.md`.

When applying documentation conventions across existing code or documentation, lazy load
`../include/common/actionable-documentation.md`.

Before writing or editing Javadoc, Javadoc links/imports, or Java API documentation, lazy load
`../include/common/write-clearly.md`, then `../include/java/javadoc.md` in that order. Do not compose or patch Javadoc
until both rules are available.

When selecting a Java platform or available project API, lazy load `../include/java/standard-apis.md`.

When creating or closing a `PipedInputStream` or waiting for a reader that uses one, lazy load
`../include/java/piped-input-stream.md`.

When writing or reviewing Java control flow that classifies a value against named alternatives, lazy load
`../include/java/control-flow.md`.

Before designing or changing a component, API, collaborator, integration boundary, construction path, or test-support
boundary, lazy load `../include/class-design.md` to preserve its caller-visible guarantees and focused responsibilities.

Before designing or implementing a feature that promises a reliable, complete, automatic, general, or deterministic
result, or a workflow that chooses, stops, or changes strategy based on a predicted cost, duration, probability,
benefit, or other future outcome, lazy load `../include/common/design-feasibility.md` to test whether the stated inputs
and authority can determine every required decision.

Before delegating work to a subagent, or when designing, updating, or executing a prompt-driven workflow that delegates
work, lazy load `../include/common/delegation.md` to assign deterministic, bounded-review, and high-judgment work to the
appropriate authority and isolate write-capable recipients.

When accessing environment-dependent state, lazy load `../include/common/environment.md` to preserve an explicit,
testable boundary.

When adding, catching, translating, or reporting a failure, lazy load `../include/common/error-handling.md` to retain
precise failure semantics and actionable diagnostics.

When considering a workaround for an external defect, lazy load `../include/common/external-workarounds.md` to require
authoritative evidence and keep any workaround narrow and removable.

When analyzing a reported or newly observed unexpected failure, discovering that an earlier action violated the
user-authorized scope, diagnosing why an agent ignored or misapplied an instruction, or designing a prevention, lazy
load `../include/common/failure-prevention.md` to classify its cause, isolate the earliest controllable decision or
delivery boundary, and verify the prevention.

When editing source code or configuration, lazy load `../include/common/semantic-blank-lines.md` to make independently
nameable stages and groups easy to scan.

When assessing an apparently absent condition, a helper's apparent limitation, or a proposed workaround, lazy load
`../include/common/limitations-and-workarounds.md` to evaluate the owning workflow and compare feasible paths.

When locating or selecting a Git commit, file history, or change to amend, backport, or replay, lazy load
`../include/common/git-history-scope.md`.

When removing, replacing, renaming, or ending compatibility for a supported contract, lazy load
`../include/common/contract-retirement.md` to classify affected contracts and verify complete retirement.

When a repeated value represents one shared concept, lazy load `../include/common/constants.md` to give it one
authoritative definition at the narrowest common layer.

Before changing testable production behavior, lazy load `../include/common/tdd.md` to establish the red-test gate.

When creating, using, or handing off temporary files or directories, lazy load
`../include/common/temporary-resources.md` to assign and verify cleanup ownership.

When designing, changing, or reviewing tests, lazy load `../include/common/testing.md` to keep their observable claims,
boundaries, and fixtures aligned.

When handling Java exceptions, lazy load `../include/java/exceptions.md` to choose precise exception semantics.

When defining Java POSIX permission values, lazy load `../include/java/constants.md` to keep the readable
permission value authoritative.

When a task is expected to run for multiple minutes, lazy load `../include/common/progress-reporting.md` to report
observable progress and calibrate estimates from current-task history.

When starting, waiting for, or resuming an external command, harness, or tool session, lazy load
`../include/common/execution-lifecycle.md` to retain its terminal outcome.

When creating, regenerating, materially revising, reviewing, or auditing a prompt, rule, or another artifact with
declared goals or contracts, lazy load `../include/common/contract-design.md` to preserve, reconcile, cover, and compact
its obligations.

When adding, moving, splitting, merging, or otherwise reorganizing a rule, prompt, skill, command, or workflow, lazy
load `../include/common/rule-organization.md` to select its authoritative owner and focused routing.

When a project-owned CLI, API, validator, generator, or other callable tool must enforce a workflow requirement, lazy
load `../include/common/rules-own-tool-requirements.md` to verify its reachable enforcement boundary.

When a recommendation, procedure, or workflow relies on an input, authority, handoff, or deferred action, lazy load
`../include/common/check-assumptions.md` to make every prerequisite available and verifiable.

When claiming that a tool, skill, subagent, command, test, or investigation has run or is unavailable outside the
reader-facing status-update route above, lazy load `../include/common/check-assumptions.md` to establish the claim from
its invocation evidence.

When answering a user question whose relevant research, authoritative source, or supported probe remains inconclusive,
lazy load `../include/common/check-assumptions.md` before drafting the answer.

When defining or changing a workflow with progress handoff, retry, or resumption, lazy load
`../include/common/monotonic-progress.md` to preserve verified state across transitions.

When defining or changing an externally visible operation that declares idempotency or can create an externally durable
effect before a caller can observe its result, lazy load `../include/common/idempotency.md` to preserve one logical
operation across retries.

When executing a maintained instruction, prompt, skill, rule, or workflow exposes an incorrect or unavailable
dependency, lazy load `../include/common/workflow-error-reporting.md` before handoff.
