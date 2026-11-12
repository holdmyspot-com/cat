---
agents: ["main", "subagents"]
---
# CAT Rule Routing

## Design Goals

- Route a design, implementation, or review decision to the foundational Design Quality and Backward Design guidance,
	and each specialized decision to its focused rule, before the reader makes it, without loading that guidance into
	sessions that make no such decision.
- Choose a route from the decision that a task asks for, not from a word that the task contains, so that correcting,
	renaming, or repeating existing text does not load the subject-matter rules of the terms in that text.
- Route a task that implements a named standardized format or another externally defined capability to its reuse
	guidance, from the stated task contract, before any project inspection, candidate search, probe, or implementation.
- Route an input-preservation decision from the stated contract before project inspection or candidate selection can
	commit to a transformation that might discard the needed distinction.
- Make each required route read complete before the task action it governs and before any task-specific commentary,
	without prescribing how the file is read.
- Load a lazily routed rule once in a conversation, and again only after the context resets or when the rule might have
	changed.
- Ensure every agent resolves task-determined companion guidance before accessing the artifact or performing the
	operation that guidance governs, rather than treating an initial routing-index read as complete.
- Keep always loaded only the routes whose decision can arise before any decision-area trigger is recognizable, and
	reach every other focused rule through one lazily loaded area index whose trigger covers the trigger of every route
	in that index.

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

Lazy load a rule once when its trigger first applies in a conversation, then reuse that loaded guidance for later
matching work. Read it again only after the conversation context resets or when the rule might have changed since it was
loaded.

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

When writing, changing, reviewing, or auditing source code or configuration, including a command-line option, launch,
build, container or sandbox setting, manifest entry, or module declaration, or accessing environment-dependent state, lazy load
`../include/common/rule-routing/code.md`.

Before designing, implementing, defining, changing, reviewing, auditing, removing, replacing, renaming, or ending
compatibility for a component, class, API, collaborator, integration or test-support boundary, construction path,
function, method, constructor, command, externally visible operation, feature, workflow, or other supported contract,
including its parameters and their validation; when adding a call that passes a method the same value as its other
calls, or noticing that calls pass a method equal values; when writing or changing code that sends or retries an
operation that can create an externally durable effect; or when selecting a platform or available project API, lazy
load `../include/common/rule-routing/interfaces.md`.

Before defining, changing, reviewing or auditing code that handles money, or choosing a numeric representation; when declaring a variable
outside a function body or an object's field, changing such a variable's value or contents, or writing or editing a
constructor that assigns a field; when a repeated value represents one shared concept, or when writing a literal that is
a directory segment, persisted filename prefix, configuration key, or generated artifact name; or when writing or
changing code that stores a file path in a durable record, lazy load `../include/common/rule-routing/values.md`.

When declaring a type, function, method, or constructor, creating a script, command, or configuration entry, or
changing the signature of one; when writing or editing a document, release notes, a changelog, the documentation of
code, a script, a command, or a configuration entry, or a source comment; or when applying documentation conventions
across existing code or documentation, lazy load `../include/common/rule-routing/documentation.md`.

Before changing testable production behavior or selecting or reporting a command as evidence that a project's build
passes, and when creating, designing, changing, reviewing, or debugging tests, a build script, pipeline, or multi-step
build, or a CI or hosted workflow; when the project's build, tests or scripts fail on a machine other than the local
one, whatever the task is called, including a release step; when running a build or test suite, starting, dispatching
or requesting a workflow run or probe that takes many minutes or draws on capacity that others share, selecting,
configuring, or reusing a build or workflow cache, building a dependency once for a workflow's jobs to share, or running
such a workflow on a remote runner; or when introducing or repairing an expensive many-unit workflow, lazy load
`../include/common/rule-routing/testing-and-builds.md`.

Before counting a conclusion drawn from two or more separate runs, such as a resumed run compared with the run it
resumed, timings before and after a change, or the results of two prompt or configuration variants, including an
ad-hoc comparison that is neither test nor build evidence, lazy load `../include/common/run-comparisons.md`.

Before choosing or applying a change, or adding functionality, that is meant to improve the performance of a program or
build, including a slow build or workflow, lazy load `../include/common/performance-changes.md`.

When a failure, a compiler, linter, or static-analysis warning or finding, a scope violation, a misapplied instruction,
an apparently absent condition or apparent limitation, a restriction, refusal or failed precondition that stops an
attempted path, an incorrect or unavailable workflow dependency, a validator or
other workflow gate rejecting generated output, or a required gate that is skipped or substituted arises or is
reported; when an external defect makes you consider a workaround or an upstream fix for a defect that a `WORKAROUND:` marker
names is adopted; when adding, catching, translating, reporting,
analyzing, preventing, suppressing, or working around one, or disabling, excluding, or relaxing a check; or before
invoking an operation that can modify multiple artifacts or whose actual write set is unproven, lazy load
`../include/common/rule-routing/failures.md`.

Before creating, reviewing, or revising an implementation plan for AI agents, delegating work, starting or
requesting a new agent session yourself, for example to test a plugin or a fresh installation in a separate session,
including from inside a CAT run, or sending information to another agent, when you receive the path of an artifact that
another agent wrote and that is too large to read in one tool output, and when designing, updating, or
executing a workflow that delegates work, exchanges information between agents, or hands off, retries, or resumes
progress; when executing a multi-step plan or goal; when dispatching independent work units to run concurrently; when
applying a skill would cause you to ask for permission or confirmation, pause, leave requested work unfinished, or
diverge from the user's intent; when creating, using, or handing off temporary files or directories; or when a task
needs a state change that something else produces, lazy load `../include/common/rule-routing/workflows.md`.

When locating or selecting a Git commit, file history, or change to amend, backport, or replay, or creating or revising
a Git commit message, lazy load `../include/common/rule-routing/git.md`.

When adding, updating, moving, splitting, merging, renaming, removing, regenerating, reviewing, or auditing a prompt
file (a rule, route index, skill, agent definition, command, workflow definition, or other prompt text that CAT ships)
in a way that changes the text any agent receives or when it receives it, when writing or changing a prompt instruction
that requires an outcome or behavior, or removing or narrowing a mechanism that such an instruction relies on, when
creating, regenerating, revising, reviewing, or auditing another artifact with declared goals or contracts, or when a
project-owned callable tool must enforce a workflow requirement, lazy load `../include/common/rule-routing/prompts.md`.
A rename or move of a prompt file whose only effect is its new path, with every reference updated so that the same text
loads under the same conditions, does not load it; verify only that no reference names the old path.

Before writing a time or date in a response, report, or other text for the user, or in a file, command, data format,
log, identifier, or commit message, including a timestamp that a tool, log, API, or command returned, lazy load
`../include/common/user-timezone.md`.

When starting, waiting for, or resuming an external command that can outlast one tool result, a harness, or a tool
session, or when defining or changing a resumable workflow's controller or its status or recovery operation, lazy load
`../include/common/execution-lifecycle.md`.

When writing a shell command that may need approval or a sandbox exception, lazy load
`../include/common/approval-ready-commands.md`.

When a recommendation, procedure, or workflow relies on an input, authority, handoff, or deferred action; before
running a search across a directory tree, or a command that prints files or records whose size you have not
established, such as generated, minified, or dependency data; when claiming that a tool, skill, subagent, command, test,
or investigation has run or is unavailable outside the reader-facing status-update route above; and before drafting an
answer to a user question whose relevant research, authoritative source, or supported probe remains inconclusive, lazy
load `../include/common/check-assumptions.md`. Lazy load it also before directly operating on a named artifact whose
exact target the task, a route or an inventory does not establish, and before accepting a stated cause or an actor's
claim about its own limits, actions or status.
