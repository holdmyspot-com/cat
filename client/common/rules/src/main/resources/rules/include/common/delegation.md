# Safe Work Delegation

## Design Goals

- Choose enough model capability and reasoning effort for each decision while accounting for failure risk, usage, and
  coordination cost: deterministic work belongs in authoritative automation, bounded work can use a less costly capable
  agent, and high-judgment decisions belong with an agent able to evaluate them.
- Isolate delegated writes and integrate them through one parent-owned boundary so concurrent verification cannot grant
  one delegate authority over another delegate's files.
<!-- Parent launch and handoff failures were previously misreported as executor failure or unavailable external state,
     causing recovery to stop at the wrong responsible boundary. -->
- Require a parent to classify an observed delegated outcome before selecting recovery, so an unreadable handoff or
  parent launch mistake cannot be reported as an executor failure or an unavailable external prerequisite.
<!-- A parent repeatedly made placeholder tool calls while direct delegation was available, then treated the absence of
     a launch receipt as a launch failure. -->
- Ensure a required delegate launch uses its documented direct operation and receipt before any wait, recovery, or
  status conclusion, so an unrelated tool result cannot be mistaken for a launch attempt.
- Make continued observation of a live delegate responsive without repeatedly polling it at a fixed short interval.
<!-- A prompt-optimizer handoff repeated decisions already fixed in its accepted implementation plan, increasing the
     message size and risking a second, inconsistent task contract. A compact reference must still give the delegate
     every task-specific authority and acceptance boundary before work begins. -->
- Let a delegate act from one accessible authoritative task contract and report the evidence needed to accept its work,
  without repeating settled decisions or omitting unresolved ones.

## Guidance

When designing, updating, or executing a prompt-driven workflow, first classify every operation as deterministic,
bounded-review, or high-judgment work.

Before assigning agent work, state the selected model capability, reasoning effort, whether delegation helps, and why.
Use a less costly capable model with low or medium reasoning for precise, routine work with predictable verification.
Use a stronger model when judgment, ambiguity, architecture, coordination, or subtle failure risk matters; raise its
reasoning effort for consequential review, complex implementation, debugging, security, migrations, or difficult
rollback. Reserve the highest effort levels for exceptional work whose risk justifies the added usage. Keep tightly
dependent or sequential decisions with one owner. Do not add subagents solely to reduce the apparent cost of a turn;
their handoff, review, and integration costs must improve the expected result.

Move deterministic operations into authoritative automation, normally a CLI. The automation owns authoritative-state
capture, derived paths and arguments, validation, generated artifacts, recovery evidence, execution, and
machine-readable diagnostics. Do not leave an agent or human to reproduce those operations.

Delegate bounded-review work to a less costly capable model when its acceptance check is materially cheaper, narrower,
or more reliable than independently performing the delegated work. Give it explicit inputs, a bounded deliverable, and
acceptance evidence. Treat its result as a candidate until the specified deterministic check or higher-authority review
accepts it.

When a delegated response must use a specific form, state that form in the task sent to the recipient. Name the
recipient's injected response contract and required form when one exists; otherwise specify every required response
component. Do not redefine that contract's statuses, keys, or terminal meanings in the task. Do not rely on a
parent-only requirement or generic handoff language.

Delegate one verification-closed behavior cluster at a time: one coherent observable outcome whose production changes
and focused tests end in an exact gate. Paths that overlap do not make independently testable lifecycle, schema, or
quality changes one cluster. Sequence those clusters through one exclusive writer or retain them at the higher
authority. Do not delegate work when accepting it would require the parent to rederive its decisions or review work as
broadly as implementing it directly.

For every write-capable delegation, supply a task contract with an exact write allowlist, verification commands,
workspace mode, and acceptance evidence. Treat the write allowlist as authority and the verification commands only as
observation: compiling or testing a wider dependency closure does not authorize the delegate to repair it. A diagnostic
whose owning path is outside the allowlist is an out-of-scope dependency to report, not work to absorb.

When an accessible authoritative artifact already fixes a delegated task's behavior and has any acceptance the workflow
requires, cite its exact location, section, and revision or content hash instead of repeating settled decisions. Include
the objective and any applicable task authority not fixed there: source snapshot, worktree or read-only mode, exact
writer allowlist, verification command, and required evidence report. If a required task-specific decision remains
unresolved, repair the governing contract or handoff before launch. Require the delegate to read the cited artifact and
return results and contract-backed exceptions, referencing retained log or artifact paths instead of copying output
unless an excerpt is needed for acceptance or recovery. A short message alone does not prove the contract is complete.

When a high-judgment reviewer prepares a task for a mechanical executor, require its returned plan amendment to make
the task decision-free: exact API/schema and failure semantics, factory and registration points, static-analysis-safe
test names or one explicit narrow exception, literal verification commands and expected results, predecessor snapshot,
and integration boundary. Reject a handoff that leaves an executor to choose a behavior, fixture, schema, responsible
component, or verification interpretation. Advice or a list of alternatives is not an executable handoff.

Send the task contract as author-provided readable text. Do not copy an encrypted, encoded, captured, or otherwise
opaque transport payload into a delegation. Before editing, the delegate must validate that it can identify the
objective, write allowlist, workspace mode, and verification commands. When the recipient has an injected terminal
response contract, the task names that contract and the delegate preserves its required outer form; the task must not
add a plaintext prefix or another incompatible response format. Otherwise the delegate returns one machine-readable
report whose `task_contract` field is `acknowledged` or `unreadable`. An unreadable contract authorizes no edits and
requires the parent to correct the delivery before retrying.

Before a parent chooses recovery or reports a delegated operation as blocked, it records the launch invocation and the
delegate's terminal receipt, then classifies the observed result against the recipient's response contract. A
contract-defined plan-ambiguity result goes to the workflow's declared high-judgment plan authority, which must update
the authoritative plan and its acceptance evidence before a fresh execution attempt. An unreadable contract, malformed
delivery, absent launch invocation, or missing terminal receipt is a parent delivery failure: repair or resend the
handoff and obtain a new receipt. Handle a completed execution failure at the boundary its evidence identifies as
responsible. Do not present a parent delivery failure as executor, plan, or external-prerequisite evidence, and do not
use it to justify a goal-level blocked state. A repeated external-prerequisite claim requires receipts that identify the
same prerequisite, unavailable state, and restoring actor or action; different handoff or execution failures begin
distinct recovery paths.

Before every delegation-related tool call, select the operation from the requested action. Establish its state from
the current tool contract and, when waiting, that operation's successful creation receipt. Classify the selected
operation as: `ready-to-launch` (a documented direct top-level spawn operation is exposed),
`delegate-running` (a successful launch returned that delegate's identity), `exec-running` (an `exec` result explicitly
returned its cell identifier), and `launch-not-invocable` (the current top-level interface exposes no direct launch
operation). A subagent launch is performed only by the documented direct top-level spawn operation from
`ready-to-launch`. Waiting for a delegate is performed only by its collaboration wait operation from
`delegate-running`. Waiting for an `exec` operation is performed only with the exact cell identifier returned by that
same live `exec` result. Never use `exec`, an `exec`-wait operation, an invented cell identifier, or a nested-script
tool catalog as a launch fallback; those actions neither launch nor diagnose a delegate. An error from another
operation does not establish launch failure: correct the selected operation and obtain its own invocation receipt.

When waiting for a live delegate, begin with a 30-second observation interval. After each timed-out observation with no
new delegate state, double the next interval, up to five minutes. Reset the interval to 30 seconds only after an
observable delegate update or a new delegate operation. A terminal result ends waiting immediately; an observation
timeout does not establish failure or authorize restart.

After the task contract is ready and the state is `ready-to-launch`, make the documented direct spawn call as the next
delegation action. Do not make a placeholder, probing, or preparatory call in place of that transition. A result that
did not invoke the documented direct spawn leaves launch unattempted: it is neither a retry nor evidence for a launch
failure, wait, recovery, status report, or blocked decision. This does not prohibit a task-required preflight that
establishes the task contract; once that preflight has established `ready-to-launch`, the next delegation action is
the direct spawn call.

When a delegation is required, inspect the direct top-level contract first and classify that launch operation. A
catalog visible inside an `exec`/script sandbox is scoped only to that sandbox and cannot select or rule out a
top-level operation. If direct spawn returns an invocation error, retain that call and its error as a parent delivery
failure, return to `ready-to-launch`, and repair or resend the handoff. If no direct launch operation is exposed, enter
`launch-not-invocable` and report only that fact for the current turn. Neither state is executor, plan, or external-
prerequisite evidence, and neither authorizes a goal-level blocked state.

Derive a write allowlist from the complete change closure before launching the delegate. For an API, schema, or
systematic migration, inventory the changed declarations, their current implementers and callers, required removals,
and focused tests; include every path required by the selected verification-closed cluster, or split the cluster at a
real integration boundary. Do not infer a narrow allowlist from the first named type when the contract requires its
implementations or callers to change.

Run concurrent write-capable delegates only in separate isolated worktrees created from an immutable shared snapshot.
Include each worktree root and snapshot identity in its task contract. Build a scheduling conflict graph from both write
sets and verification dependencies; file-disjoint tasks are not independent when either task's verification consumes
the other's changing output. If the actor responsible for workspace integration cannot create and later integrate
isolated results, use one exclusive writer in the shared worktree instead of improvising temporary indexes, worktrees,
commits, or merge commands in prompt prose.

Use one readiness review to map several upcoming clusters before serial implementation begins. Split every discovered
missing prerequisite into its own verification-closed cluster with a named responsible component, then run only
graph-independent clusters in parallel. This reduces rediscovery and handoff latency without allowing a consumer to
start before its producer's accepted snapshot exists.

Maintain a rolling readiness window rather than reviewing one blocked cluster at a time. Before an accepted cluster
unblocks a sequential successor, the parent maps the next eligible dependency frontier: each cluster's exact write set,
accepted-snapshot inputs, focused gate, static-analysis scope, and integration consumer. The high-judgment reviewer
resolves only genuine contract gaps in that window and returns one decision-free amendment plus a conflict graph.
Launch every graph-independent write cluster concurrently only in the isolated worktrees that graph assigns; retain
one exclusive writer for every shared-worktree chain. Do not delay an already mechanical eligible cluster for an
unrelated review, and do not launch a consumer until every producer edge has its accepted receipt.

Make quality closure part of the producer cluster, not a later repair queue. Before a parent accepts or hands off a
cluster, run its compile and exact-path Checkstyle/PMD preflight as soon as its owned paths exist, then run its focused
behavior gate. An owned compiler or static-analysis finding with a plan-determined repair remains with that same
executor and allowlist; it is not a Sol-high escalation or a new repair agent. Record known unrelated module-wide
quality failures as an environmental baseline and use the exact owned-path gates while developing; run broad quality
gates only at the plan's declared integration boundaries.

A delegate never integrates its own result. The parent or an authoritative integration operation first rejects changed
paths outside the allowlist, then integrates accepted results one at a time and runs the combined affected checks. A
clean integration worktree may use the repository's approved merge operation. A dirty parent worktree must not receive
a direct merge or cherry-pick; use an authoritative validated patch replay that preserves its existing changes, or keep
the result isolated until a clean integration boundary exists. Start a dependent delegation only from the resulting
integrated snapshot.

Keep high-judgment work with the higher-level model: semantic design, intent, topic boundaries, earliest complete
placement, conflict resolution, security-sensitive decisions, and any result whose verification would require redoing
the same analysis. Do not delegate merely to create an appearance of independent review.

Delegate when the independent assignment and its acceptance check justify the coordination cost; otherwise continue
directly.
