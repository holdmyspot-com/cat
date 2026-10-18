---
agents: ["main", "subagents"]
---
# Default Rule Routing

## Design Goals

- Route a design, implementation, or review decision to the foundational Design Quality and Backward Design guidance,
  and each specialized decision to its focused rule, before the reader makes it, without loading that guidance into
  sessions that make no such decision.
- Route an input-preservation decision from the stated contract before project inspection or candidate selection can
  commit to a transformation that might discard the needed distinction.
- Make each required route read complete before the task action it governs, without prescribing how the file is read.

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
protocol handling, cryptography, scheduling, and decimal arithmetic.

For a task that met the standardized-capability trigger above, choose its route as follows. If it accepts one written
form and rejects another because of how it is written, rather than only because of the value ordinary processing
returns, read `../include/common/prefer-reuse.md`, then `../include/common/preserve-required-distinctions.md` in this
exact order. This includes a canonical spelling that must be accepted while another spelling of the same external
identifier, timestamp, number, or configuration value must be rejected; the task need not first prove that the chosen
API will collapse the forms.

Otherwise, read only `../include/common/prefer-reuse.md`. This alternative applies only after the standardized-capability
trigger above is true and the contract permits the representations to be treated alike. When that trigger is false,
neither read applies. Reading only the first file leaves the paired-input route incomplete; do not read another rule,
inspect the project, or take any other task action after that read.

Treat every required read in this routing file as one route action: after choosing its condition, complete all of its
reads in the stated order before any other task action. A successful first read is neither a completed route nor
authority to discover its remainder; do not use it to infer, search for, or try another filename. Use the filenames in
the route as literal route targets. If any required read fails, stop and report the routing failure instead of
inspecting the project or choosing an alternative. The route, rather than the task's source language, topic, or a
remembered rule label, selects the required files.

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

Before choosing the numeric representation of a calculation, or of a numeric parameter or result, that is not money,
lazy load `../include/common/numeric-precision.md`.

When writing or changing a boolean expression that contains a negation, lazy load
`../include/common/boolean-negation.md`.

When defining, changing, reviewing, or auditing type, function, or method names, or the names of variables declared
outside a function body, or changing a type's responsibility without renaming it, lazy load
`../include/common/identifier-naming.md`.

When defining, changing, or auditing terminology used for a shared concept across source code, configuration,
documentation, commands, or rules, lazy load `../include/common/consistent-terminology.md`. This includes naming a
shared concept while designing a format, interface, workflow, or proposal, and noticing during any other work that two
terms may denote one shared concept.

When defining, changing, or reviewing parameter validation for a caller-facing operation, lazy load
`../include/common/parameter-validation.md`.

When defining a function, method, constructor, or command, or changing its parameters, while an overload or another
operation of the same API takes some of the same inputs, even in another type or representation, lazy load
`../include/common/consistent-parameters.md`.

When writing or editing a product, legal, or user-facing workflow document, an overview, release notes, or a
changelog, lazy load `../include/documentation/standalone-documents.md`.

When writing or editing the documentation of a module, type, method, function, constructor, script, command, or
configuration entry, including API documentation and command help text, lazy load
`../include/documentation/interface-contracts.md`.

When declaring a variable outside a function body, such as a field, constant, or module-level variable, or changing the
value or contents of an existing one, lazy load `../include/documentation/declared-values.md`.

When writing or editing a source comment, including a comment in a prompt or rule file, lazy load
`../include/documentation/source-comments.md`.

When applying a skill would cause you to ask for permission or confirmation, pause, leave requested work unfinished, or
diverge from the user's intent, lazy load `../include/common/skill-interruption-reporting.md` before taking that action.

Before writing or editing a reader-facing update whose new evidence requires an actor to correct, narrow, or otherwise
change an action, read `../include/common/write-clearly/unexpected-result.md`.

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

When editing the layout of a method chain, lazy load `../include/common/method-chain-layout.md`.

When declaring an object's field, or writing or editing a constructor that assigns one, lazy load
`../include/common/field-initialization.md`.

When writing or changing code that passes a resource it created to a constructor, or a constructor that receives or
creates a resource that must be released, lazy load `../include/common/construction-failure-cleanup.md`.

When creating or closing a `PipedInputStream` or waiting for a reader that uses one, lazy load
`../include/java/piped-input-stream.md`.

When writing or reviewing Java control flow that classifies a value against named alternatives, lazy load
`../include/java/control-flow.md`.

Before recommending, designing, implementing, or reviewing a change to code, configuration, a prompt, a workflow, or a
plan, lazy load `../include/common/backward-design.md`, then `../include/common/design-quality.md`. A local rename,
formatting edit, or other mechanical change that preserves the existing design does not need them.

Before creating, reviewing, or revising an implementation plan for AI agents to execute, lazy load
`../include/common/implementation-planning.md`. A checklist of steps you are about to perform yourself in the current
task is not such a plan.

Before designing or changing a component, API, collaborator, integration boundary, construction path, or test-support
boundary, lazy load `../include/class-design.md` to preserve its caller-visible guarantees and focused responsibilities.

When creating, moving, or renaming a top-level implementation or test type, lazy load
`../include/common/type-organization.md`.

Before designing or implementing a feature that promises a reliable, complete, automatic, general, or deterministic
result, or a workflow that chooses, stops, or changes strategy based on a predicted cost, duration, probability,
benefit, or other future outcome, lazy load `../include/common/design-feasibility.md` to test whether the stated inputs
and authority can determine every required decision.

Before delegating work to a subagent, or when designing, updating, or executing a prompt-driven workflow that delegates
work, lazy load `../include/common/delegation.md` to assign deterministic, bounded-review, and high-judgment work to the
appropriate authority and isolate write-capable recipients.

Before sending a task, result, correction request, or other information to another agent, or when designing a
workflow whose agents exchange information, lazy load `../include/common/agent-handoffs.md`.

When accessing environment-dependent state, lazy load `../include/common/environment.md` to preserve an explicit,
testable boundary.

When adding, catching, translating, or reporting a failure, lazy load `../include/common/error-handling.md` to retain
precise failure semantics and actionable diagnostics.

When considering a workaround for an external defect, lazy load `../include/common/external-workarounds.md` to require
authoritative evidence and keep any workaround narrow and removable.

When debugging a failed CI or hosted-workflow step, lazy load `../include/common/ci-debugging.md` to choose the
authoritative local or hosted reproduction boundary before retrying.

When analyzing a reported or newly observed unexpected failure, discovering that an earlier action violated the
user-authorized scope, diagnosing why an agent ignored or misapplied an instruction, or designing a prevention, lazy
load `../include/common/failure-prevention.md` to classify its cause, isolate the earliest controllable decision or
delivery boundary, and verify the prevention.

When the Failure Prevention analysis confirms a reproducible failure involving prompt, rule, or workflow guidance and
the affected guidance is user-owned, lazy load `../include/common/failure-regression-promotion.md` and apply its
correction and regression procedure before resuming dependent work. The first qualifying failure is the trigger.
User-owned guidance is under the current project's `.cat/rules/` tree or the current user's `${HOME}/.cat/rules/` tree,
or is CAT plugin source while the user is that plugin's developer. Do not load this rule for installed/cache,
third-party, or unknown-owner guidance. A subagent does not load this rule or edit that guidance: it reports the
confirmed failure, the affected guidance and the attribution evidence to the agent that delegated its work, then
continues only work that does not depend on the correction.

Before invoking an operation that can modify multiple artifacts or whose actual write set is unproven, lazy load
`../include/common/failure-prevention.md` to use its transactional snapshot, scope-validation, conditional-rollback,
and acceptance procedure.

When a compiler, linter, or static-analysis check reports a warning or finding, lazy load
`../include/common/diagnostic-suppression.md` before choosing how to resolve it, including before searching the project
for how it handled the same finding. Also lazy load it before suppressing any other diagnostic, or before disabling,
excluding, or relaxing a check.

When editing source code or configuration, lazy load `../include/common/semantic-blank-lines.md` to make independently
nameable stages and groups easy to scan.

When assessing an apparently absent condition, a helper's apparent limitation, or a proposed workaround, lazy load
`../include/common/limitations-and-workarounds.md` to evaluate the owning workflow and compare feasible paths.

When locating or selecting a Git commit, file history, or change to amend, backport, or replay, lazy load
`../include/common/git-history-scope.md`.

When creating or revising a Git commit message, lazy load `../include/common/commit-messages.md`.

When removing, replacing, renaming, or ending compatibility for a supported contract, lazy load
`../include/common/contract-retirement.md` to classify affected contracts and verify complete retirement.

When a repeated value represents one shared concept, lazy load `../include/common/constants.md` to give it one
authoritative definition at the narrowest common layer.

Before changing testable production behavior, lazy load `../include/common/tdd.md` to establish the red-test gate.

When creating, using, or handing off temporary files or directories, lazy load
`../include/common/temporary-resources.md` to assign and verify cleanup ownership.

When writing or changing code that stores a file path in a file, database, or other durable record, lazy load
`../include/common/persisted-paths.md`.

When designing, changing, or reviewing tests, lazy load `../include/common/testing.md` to keep their observable claims,
boundaries, and fixtures aligned.

Before selecting or reporting a command as evidence that a project's build passes, lazy load
`../include/common/build-verification.md`.

When handling Java exceptions, lazy load `../include/java/exceptions.md` to choose precise exception semantics.

When defining Java POSIX permission values, lazy load `../include/java/constants.md` to keep the readable
permission value authoritative.

When a task is expected to run for multiple minutes, lazy load `../include/common/progress-reporting.md` to report
observable progress and calibrate estimates from current-task history.

When starting, waiting for, or resuming an external command, harness, or tool session, lazy load
`../include/common/execution-lifecycle.md` to retain its terminal outcome.

When a task needs a state change that something else produces, such as a hosted build finishing, a background process
exiting or a subagent returning, lazy load `../include/common/external-waits.md` before you check that state or continue
other work.

When creating, regenerating, materially revising, reviewing, or auditing a prompt, rule, or another artifact with
declared goals or contracts, lazy load `../include/common/contract-design.md` to preserve, reconcile, cover, and compact
its obligations.

When adding, moving, splitting, merging, or otherwise reorganizing a rule, prompt, skill, command, or workflow, lazy
load `../include/common/rule-organization.md` to select its governing prompt file and focused routing.

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
