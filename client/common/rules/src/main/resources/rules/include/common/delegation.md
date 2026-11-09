# Safe Work Delegation

## Design Goals

- Allocate workflow work to the least costly authority that can verify or decide it: deterministic work to authoritative
	automation, bounded review to independently checkable delegation, and high-judgment decisions to the authority able to
	evaluate them.
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
- Wait for a live delegate by blocking until it returns, rather than observing it at intervals.
<!-- A parent sent one subagent four topics, two of them independent of the others, with one shared reading list; after
     45 minutes the subagent had read about 15 rule files and changed no file. -->
- Split delegated work into as many independent subtasks as its dependencies allow, each run by its own subagent with
	only the context and focused checks it needs and each large enough that its own work outweighs a subagent's fixed
	cost, so independent work proceeds concurrently, no delegate spends its time on context or checks that belong to
	other work, and the full build runs once, after integration.
<!-- A parent gave one implementer five parts of a work item and a reading list of whole design sections; after more
     than two hours the implementer had committed nothing. -->
- Point each subtask at the exact passages its decisions depend on, so that it spends its time on its deliverable
	rather than on searching documents for them.
<!-- A coordinator briefed every unit of a multi-unit implementation to its most costly implementer, although several
     units were mechanical, such as a configuration-path change, a rename and applying a ready patch, because each brief
     mixed open design choices with the edits that follow from them. -->
- Run each delegated implementation task on the least costly available agent whose result a build, test or diff check
	can accept, by settling the choices a task leaves open before delegating the edits that follow from them, so that
	mechanical edits neither wait for nor pay for judgment that they do not need.
<!-- A parent sent a credential-handling change, whose brief already settled every behavior, to its most costly
     implementer as one task, and later sent three more fixes to that same delegate as a follow-up message, so neither
     the split nor the agent was chosen again. -->
- Choose the split and the agent for every piece of delegated work, including work added to a delegate that is running
	or has returned, from what its contract still leaves open, so that neither the importance of a subject nor a
	delegate's earlier choice selects the agent.
<!-- A parent spent most of a long assignment on readiness reviews, inventories, and approval stops between small
     already authorized steps, while the connected deliverable that its acceptance required stayed unbuilt. -->
- Let a delegate carry an authorized assignment through implementation, focused verification, and routine repairs to
	its own gate, with the parent deciding only the open questions the task names and accepting the result only at the
	completed assignment, so delegated time goes to deliverable work rather than to approval stops or to reviews that
	resolve no decision.
<!-- A parent coordinating about 24 subagents spent about a quarter of a long run re-checking returned evidence, mostly
     recomputing hashes that showed only that files were unaltered, while the claim that decided acceptance was one
     result inside that evidence; one result was often checked by its delegate, a review subagent, and the parent. -->
- Have a parent accept or reject a delegated result once, when the delegate reports completion, by confirming each
	claimed outcome that the acceptance condition decides through the check that decides it, and reuse that decision
	until an input it depended on changes, so acceptance time goes to the claims that decide it rather than to evidence
	that decides nothing or to repeated checks of the same result.

## Guidance

When designing, updating, or executing a prompt-driven workflow, first classify every operation as deterministic,
bounded-review, or high-judgment work.

Move deterministic operations into authoritative automation, normally a CLI. The automation owns authoritative-state
capture, derived paths and arguments, validation, generated artifacts, recovery evidence, execution, and
machine-readable diagnostics. Do not leave an agent or human to reproduce those operations.

Delegate bounded-review work to a cheaper model only when its acceptance check is materially cheaper, narrower, or more
reliable than independently performing the delegated work. Give it explicit inputs, a bounded deliverable, and
acceptance evidence. Treat its result as a candidate until the parent accepts it at the completed assignment, as the
acceptance guidance below describes.

When a delegated response must use a specific form, state that form in the task sent to the recipient. Name the
recipient's injected response contract and required form when one exists; otherwise specify every required response
component. Do not redefine that contract's statuses, keys, or terminal meanings in the task. Do not rely on a
parent-only requirement or generic handoff language.

Before delegating, decompose the work into as many independent subtasks as possible and give each subtask its own
subagent. Make each subtask the smallest independent piece whose own work clearly outweighs a subagent's fixed cost:
reading its task contract and required rules, running its gates, and the parent's review and integration of its result.
Without a measured fixed cost, aim for about five minutes of a subagent's work, and do not split a piece further when
its subtasks would spend more time on that fixed cost than on their own work. Size a subtask by its deliverable work: a
production change with its own gate, or an investigation that resolves a named decision. Delegate a subtask whose only
output is an assessment, inventory, or review, including a further review of a result that its delegate or the parent
already checked, only when its result resolves a named decision that selects or changes implementation work. When
choosing which work to delegate next while executing a plan, lazy load `implementation-planning.md`. Each subtask is one
verification-closed behavior cluster: one coherent observable outcome whose production changes and focused tests end in
its own exact gate. Paths that overlap do not make independently testable lifecycle, schema, or quality changes one
cluster. A piece that cannot be split further without losing its own exact gate stays one subtask, however long it
takes. Launch the subtasks that the conflict graph below shows as independent concurrently. A subtask that consumes
another subtask's output waits for that output, and subtasks whose write sets overlap run in sequence through one
exclusive writer or stay with the higher authority. Do not delegate work when accepting it would require the parent to
rederive its decisions or review work as broadly as implementing it directly.

Before briefing an implementation task, separate the choices it leaves open, such as an interface, a behavior, a
placement or a wording, from the edits that follow once those choices are made. Settle the choices first, with the
higher-level model or in a task contract that leaves nothing to decide, then delegate the edits as their own tasks;
when those edits are too small to outweigh a subagent's fixed cost, whoever settles the choices makes them. Give each
task to the least costly available agent whose result a build, a test or a check of its diff can accept, and keep with
the higher-level model the high-judgment work listed near the end of this rule. When choosing which of CAT's implementer
agents runs a delegated task, lazy load `delegation/implementer-agents.md`.

Decompose and choose an agent this way for every piece of delegated work, including more work for a delegate that is
running or has returned: that work is a new task, so split it and choose its agent as for a new launch, and send it to
that delegate only when its contract selects that delegate's agent and the work needs that delegate's context. A task
whose subject is security-sensitive or important keeps no high-judgment work once its contract settles the decisions
that the subject raises; its edits go to the agent that the contract selects.

Give each subtask its own task contract that carries only what that subtask needs, including a reading list limited to
the files its decisions depend on. Name in each entry the sections, declarations or line ranges that hold those
decisions, rather than a whole document or a section that it must search. Do not send several subtasks one shared reading list. A subtask's gates are its
focused checks: the compilation, static analysis and tests that its owned paths affect. The parent runs the full build
once, after integrating the subtasks' results, so that concurrent subtasks neither run it nor wait for one another's.

For every write-capable delegation, supply a task contract with an exact write allowlist, verification commands,
workspace mode, and acceptance evidence. The contract requires the delegate's completion report to name, for each
outcome it claims, the check that decides it, as a command or a precise observation, that check's result, and where the
check's full output is. The contract gives the delegate authority to complete the implementation, its focused
verification, and routine repairs within that scope, through to its own gate. Require an intermediate parent decision
only for an unresolved contract question, authority boundary, or consequential design choice that the task names. Accept
and integrate the result at the completed assignment boundary; do not insert approval stops between steps the contract
already authorizes. Treat the write allowlist as authority and the verification commands only as observation: compiling
or testing a wider dependency closure does not authorize the delegate to repair it. A diagnostic whose owning path is
outside the allowlist is an out-of-scope dependency to report, not work to absorb.

When a delegate reports completion, accept or reject its result once, at that point. For each claimed outcome that the
acceptance condition decides, rerun or inspect the decisive check that the report names, and compare its result with
the claim; do not search the delegate's other evidence for it. When the report names no decisive check for such an
outcome, run the check that the acceptance condition specifies. Reject the result when a decisive check fails or
contradicts the claim. Do not check the integrity or provenance of the returned files, for example by recomputing
payload hashes, re-validating archive contents, or comparing source identities, unless the task names tampering,
corruption in transit, or a stale input as a risk; that check then decides the outcome the risk threatens. Reuse an
accepted result afterwards: a later review, integration, or status report does not check it again unless an input it
depended on changed.

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

While a live delegate runs, lazy load `external-waits.md` and wait on the delegate's wait operation until it returns.
A wait operation that times out before the delegate returns does not establish failure or authorize a restart;
wait on the same delegate again.

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

Run a write-capable delegate in its own isolated worktree, created from an immutable shared snapshot, whenever another
delegate or the parent works in the same project while it runs. An isolated worktree has its own working files and its
own repository state: named references such as branches and tags, configuration, hooks, and set-aside changes such as
a stash. It shares none of that state with the parent's repository or with another delegate's, so a command that the
delegate runs there cannot change a reference, setting, hook, or set-aside change that another writer depends on.
Create it as a separate repository from the repository that holds the snapshot's objects, with an object store that
reads those objects without copying them when the version-control tool supports that, and check out the snapshot. Such
a worktree works only while the source keeps those objects and the delegate can read them: use it only until the
delegate's result is integrated, and until then do not prune the source's objects or run an aggressive garbage
collection on it. Give the delegate an independent copy of the repository instead when its worktree must outlive that
period or the delegate cannot read the source's object store. For example, in Git a `git worktree` of the parent's
repository shares its references, configuration, hooks, and stash, so it is not isolated; `git clone --shared <source>
<worktree root>` followed by a checkout of the snapshot commit is, and a clone without `--shared` is an independent
copy. The isolated worktree changes only where the delegate works: its result still reaches the parent through the
integration described below.

Include each worktree root and snapshot identity in its task contract. Build a scheduling conflict graph from both write
sets and verification dependencies; file-disjoint tasks are not independent when either task's verification consumes
the other's changing output. If the actor responsible for workspace integration cannot create and later integrate
isolated results, use one exclusive writer in the shared worktree instead of improvising temporary indexes, worktrees,
commits, or merge commands in prompt prose.

Use a readiness review only for upcoming clusters whose contracts still contain a genuine gap: a behavior, interface,
responsible component, or verification that the plan has not decided. Never place one in front of a cluster whose
contract is already decision-free; launch that cluster. When several upcoming clusters have such gaps, map them in one
review before their implementation begins. Split every discovered missing prerequisite into its own
verification-closed cluster with a named responsible component, then run only graph-independent clusters in parallel.
This reduces rediscovery and handoff latency without allowing a consumer to start before its producer's accepted
snapshot exists.

Maintain a rolling readiness window rather than reviewing one blocked cluster at a time. Before an accepted cluster
unblocks a sequential successor, the parent maps the next eligible dependency frontier: each cluster's exact write set,
accepted-snapshot inputs, focused gate, static-analysis scope, and integration consumer. The high-judgment reviewer
resolves only genuine contract gaps for clusters in that window that are not yet mechanical, and returns one
decision-free amendment plus a conflict graph. Launch every graph-independent write cluster concurrently only in the
isolated worktrees that graph assigns; retain one exclusive writer for every shared-worktree chain. Do not delay an
already mechanical eligible cluster for an unrelated review, and do not launch a consumer until every producer edge has
its accepted receipt.

Make quality closure part of the producer cluster, not a later repair queue. Before a parent accepts or hands off a
cluster, run the checks the project configures for its owned paths, when it has any. Run configured compilation and
static-analysis checks as soon as those paths exist, then any configured focused behavior gate. An owned compiler or
static-analysis finding with a plan-determined repair remains with that same executor and allowlist; it is not an
escalation to ${CAT_RULE_STRONGEST_IMPLEMENTER} or a new repair agent. Record known unrelated module-wide quality
failures as an environmental baseline and use the exact owned-path gates while developing; run broad quality gates
only at the plan's declared integration boundaries.

A delegate never integrates its own result. The parent or an authoritative integration operation first rejects changed
paths outside the allowlist, then integrates accepted results one at a time, runs the combined affected checks, and then
runs the full build once. A clean integration worktree may use the repository's approved merge operation. A dirty
parent worktree must not receive a direct merge or cherry-pick; use an authoritative validated patch replay that
preserves its existing changes, or keep the result isolated until a clean integration boundary exists. Start a
dependent delegation only from the resulting integrated snapshot.

Keep high-judgment work with the higher-level model: semantic design, intent, topic boundaries, earliest complete
placement, conflict resolution, security-sensitive decisions, and any result whose verification would require redoing
the same analysis.

When a cheaper agent is available and delegation satisfies these conditions, use it; otherwise continue directly.
