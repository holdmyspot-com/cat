---
agents: ["main", "subagents"]
---
# CAT Rule Routing

## Design Goals

- Route a design, implementation, or review decision to the foundational Design Quality and Backward Design guidance,
	and each specialized decision to its focused rule, before the reader makes it, without loading that guidance into
	sessions that make no such decision.
- Route an input-preservation decision from the stated contract before project inspection or candidate selection can
	commit to a transformation that might discard the needed distinction.
- Make each required route read complete before the task action it governs, without prescribing how the file is read.
- Ensure every agent resolves task-determined companion guidance before accessing the artifact or performing the
	operation that guidance governs, rather than treating an initial routing-index read as complete.
- Keep always loaded only the routes whose decision can arise before any decision-area trigger is recognizable, and
	reach every other focused rule through one lazily loaded area index whose trigger covers that rule's trigger.

## Guidance

Before choosing a task route, classify the requested action rather than matching a word. When the task only corrects,
documents, renames, or repeats existing text while preserving its meaning, choose only routes that govern editing that
text; do not read a subject-matter rule because the text contains its terminology. For example, correcting a term in a
document does not ask the reader to implement, select, validate, or otherwise decide anything about that term. A word
in quoted text, an example, a filename, or existing material is not a request to make the decision a route governs.
When the task does ask for that decision, choose the route from the decision itself.

When a task requests parsing, serializing, validating, or otherwise implementing a named standardized format or another
externally defined capability, choose its complete initial route from the stated task contract before any project
inspection, candidate search, probe, or implementation: read `../include/common/prefer-reuse.md` and complete the route
that it gives for that contract. This trigger includes a standard-format parser or serializer, protocol handling,
cryptography, scheduling, and decimal arithmetic. When the trigger is false, this read does not apply.

Treat every required read in this routing file, or in an area index that it routes to, as one route action: after
choosing its condition, complete all of its reads in the stated order before any other task action. When a selected
routing rule, including one loaded this way, contains conditional lazy-load directives, evaluate each directive against
the task's declared artifact paths and operations before accessing an artifact or performing an operation the directive
governs. Load every matching companion, then repeat this evaluation for each newly loaded rule until no task-determined
directive remains. Record the selected path or operation, the directive, and each successful read. A successful first
read or index read is neither a completed route nor authority to discover its remainder, and it does not authorize the
governed access while an applicable companion remains unread; do not use it to infer, search for, or try another
filename. Use the filenames in the route as literal route targets. Do not load a companion whose condition is absent
merely because its parent index was selected. If any required read fails, stop and report the routing failure instead
of inspecting the project, performing the governed access or operation, or choosing an alternative. The route, rather
than the task's source language, topic, or a remembered rule label, selects the required files.

A task-specific response that states an intended inspection, plan, implementation, test, or conclusion is task-bearing
for this route. Before the required reads, do not announce such work; either perform the required reads first or give no
task-specific commentary. A generic progress acknowledgement that does not name or commit to a task action is allowed.

Outside the externally defined-capability route above, before selecting or changing a component, API, storage mapping,
or configuration that transforms input, successfully read `../include/common/preserve-required-distinctions.md` when
the contract requires an operation to make different decisions for two representations that the transformation could
collapse. This includes treating an absent configuration field differently from an explicit value, preserving identifier
case, and distinguishing an omitted timestamp offset from an explicit one. Do not load this rule when the contract
expressly permits the forms to be treated alike.

When defining, changing, or auditing terminology used for a shared concept across source code, configuration,
documentation, commands, or rules, lazy load `../include/common/consistent-terminology.md`. This includes naming a
shared concept while designing a format, interface, workflow, or proposal, and noticing during any other work that two
terms may denote one shared concept.

Before writing or editing a reader-facing update whose new evidence requires an actor to correct, narrow, or otherwise
change an action, read `../include/common/write-clearly/unexpected-result.md`.

This includes a failed test, review, or observed result that requires a corrective action even when the task never
states an earlier plan. Do this after the task gives the update's facts and before drafting it. This is the complete
route for that update: do not add a general reporting or failure-analysis rule merely because the update names a
failure. Add a failure-analysis route only when the task also asks to investigate its cause, determine why it occurred,
or design its prevention. Do not replace it with the later general one-file Clear Writing route.

Before writing or editing another user response, prompt or rule file, source comment or Javadoc, command help text, or
error message whose new evidence does not change the action the author planned to take, select its route after the task
gives the reader-facing content and before drafting it. If that reader-facing update reports a tool, skill, subagent,
command, test, or investigation as completed, failed, or unavailable, read `../include/common/write-clearly.md`, then
`../include/common/check-assumptions.md` in that order as one route. A task-supplied result is still a reported claim.
Otherwise read only `../include/common/write-clearly.md`. Do not load this route or the one above merely because a task
mentions prose that the reader will not receive.

Before recommending, designing, implementing, or reviewing a change to code, configuration, a prompt, a workflow, or a
plan, lazy load `../include/common/backward-design.md`, then `../include/common/design-quality.md`. A local rename,
formatting edit, or other mechanical change that preserves the existing design does not need them.

When writing, changing, reviewing, or auditing source code or configuration, including a command-line option, launch or
build setting, manifest entry, or module declaration, or accessing environment-dependent state, lazy load
`../include/common/rule-routing/code.md`.

Before designing, implementing, defining, changing, reviewing, auditing, removing, replacing, renaming, or ending
compatibility for a component, class, API, collaborator, integration or test-support boundary, construction path,
function, method, constructor, command, externally visible operation, feature, workflow, or other supported contract,
including its parameters and their validation; when adding a call that passes a method the same value as its other
calls, or noticing that calls pass a method equal values; or when selecting a platform or available project API, lazy
load `../include/common/rule-routing/interfaces.md`.

Before defining or changing code that handles money or choosing a numeric representation; when declaring a variable
outside a function body or an object's field, changing such a variable's value or contents, or writing or editing a
constructor that assigns a field; when a repeated value represents one shared concept; or when writing or changing code
that stores a file path in a durable record, lazy load `../include/common/rule-routing/values.md`.

When writing or editing a document, release notes, a changelog, the documentation of code, a script, a command, or a
configuration entry, or a source comment, or applying documentation conventions across existing code or documentation,
lazy load `../include/common/rule-routing/documentation.md`.

Before changing testable production behavior or selecting or reporting a command as evidence that a project's build
passes, and when creating, designing, changing, reviewing, or debugging tests, a build script, pipeline, or multi-step
build, or a CI or hosted workflow, or running such a workflow on a remote runner, lazy load
`../include/common/rule-routing/testing-and-builds.md`.

When a failure, a compiler, linter, or static-analysis warning or finding, a scope violation, a misapplied instruction,
an apparently absent condition or apparent limitation, or an incorrect or unavailable workflow dependency arises or is
reported; when an external defect makes you consider a workaround; when adding, catching, translating, reporting,
analyzing, preventing, suppressing, or working around one, or disabling, excluding, or relaxing a check; or before
invoking an operation that can modify multiple artifacts or whose actual write set is unproven, lazy load
`../include/common/rule-routing/failures.md`.

Before creating, reviewing, or revising an implementation plan for AI agents, delegating work, or sending information to
another agent, and when designing, updating, or executing a workflow that delegates work, exchanges information between
agents, or hands off, retries, or resumes progress; when applying a skill would cause you to ask for permission or
confirmation, pause, leave requested work unfinished, or diverge from the user's intent; when creating, using, or
handing off temporary files or directories; or when a task is expected to run for multiple minutes or needs a state
change that something else produces, lazy load `../include/common/rule-routing/workflows.md`.

When locating or selecting a Git commit, file history, or change to amend, backport, or replay, or creating or revising
a Git commit message, lazy load `../include/common/rule-routing/git.md`.

When creating, regenerating, revising, reviewing, auditing, adding, moving, splitting, merging, or reorganizing a
prompt, rule, skill, command, workflow, or another artifact with declared goals or contracts, or when a project-owned
callable tool must enforce a workflow requirement, lazy load `../include/common/rule-routing/prompts.md`.

Before writing a time or date in a response, report, or other text for the user, or in a file, command, data format,
log, identifier, or commit message, including a timestamp that a tool, log, API, or command returned, lazy load
`../include/common/user-timezone.md`.

When starting, waiting for, or resuming an external command, harness, or tool session, lazy load
`../include/common/execution-lifecycle.md`.

When a recommendation, procedure, or workflow relies on an input, authority, handoff, or deferred action; before
running a search across a directory tree, or a command that prints files or records whose size you have not
established, such as generated, minified, or dependency data; when claiming that a tool, skill, subagent, command, test,
or investigation has run or is unavailable outside the reader-facing status-update route above; and before drafting an
answer to a user question whose relevant research, authoritative source, or supported probe remains inconclusive, lazy
load `../include/common/check-assumptions.md`.
