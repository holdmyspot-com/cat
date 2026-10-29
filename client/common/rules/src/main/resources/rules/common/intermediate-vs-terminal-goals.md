# Intermediate versus Terminal Goals

## Design Goals

- Keep workflow execution directed at the user-authorized terminal outcome when new tasks, repairs, or discoveries arise
	during the work.
- Prevent intermediate success or a partial scope from being reported or treated as completion without evidence for the
	terminal outcome across the complete user-authorized scope, including every required terminal gate.
- Require every terminal gate to be closed only by evidence that directly establishes its own claim, distinguishing an
	available prerequisite from an executed behavior and its required effect.
- Keep goal status tied to user-authorized constraints and unavailable external prerequisites, rather than to the
	executor's temporary turn, context, polling, or duration limits.
- Keep a locally creatable prerequisite and its unrun local acceptance gate from being mistaken for an unavailable
	external prerequisite.
<!-- A delegate's local `BLOCKED` label was previously treated as authority to stop the parent goal even when the
     parent could repair the handoff, clarify the plan, or take another in-scope recovery action. -->
- Keep a recoverable child response from changing an active goal's status merely because its response uses a
	`BLOCKED` label.
<!-- Different tool and handoff failures were previously counted together as one blocker without proving one unavailable
     external prerequisite or exhausting the recovery that its owner could perform. -->
- Require a blocked-goal transition to identify the unavailable external prerequisite and exhaust supported in-scope
	recovery before it can stop the terminal workflow.
<!-- A malformed wait was incorrectly treated as evidence that a different launch interface was unavailable, allowing a
     local operation-selection error to stop the terminal workflow. -->
- Ensure a goal-status transition treats availability as unknown until the named external prerequisite's own
	authoritative interface establishes it, so an unrelated or invalid local operation cannot create a blocker.

## Guidance

For every workflow, name the terminal goal and the acceptance evidence that proves it. Classify plans, staging
artifacts, repairs, migrations, investigations, commits, recorded transitions, and verification as intermediate tasks
unless their evidence proves every terminal acceptance condition.

Record every required terminal gate and its required evidence before execution. Mark each gate passed, failed, unrun, or
explicitly excluded by the user; do not infer a passed state from a related check. A failed or unrun required gate is an
unmet terminal condition. A clean build, an inspected result, a repaired intermediate artifact, or an existing sequence
of commits cannot substitute for the gate that the workflow requires.

Match evidence to the claim each terminal gate makes. Evidence that establishes only an enabling condition—such as
installation, configuration, authorization, discovery, connection, initialization, or process start—may pass only an
availability gate. It cannot pass a behavior or outcome gate. For those gates, require evidence from the relevant
execution boundary that the required behavior ran and produced its required effect. When availability itself is the
terminal outcome, record availability as that gate's claim.

When new work arises, such as a user instruction or a discovered prerequisite, failure, repair, workaround, or
implementation detail, classify it before acting. It supports the current terminal goal unless the user explicitly
replaces or expands the requested terminal outcome; being necessary to proceed does not make work a goal change. Record
its own completion evidence separately. After it completes, re-evaluate the unchanged terminal conditions and continue
from the next unmet condition. Do not report completion, return control as complete, or discard required follow-up work
on intermediate evidence.

Do not mark a goal blocked because the current executor reaches a turn, token, context, polling, tool-wait, or estimated
duration limit. Those limits constrain the current attempt, not the user-authorized outcome: record the last verified
state and the next required action, then resume it in a later attempt. Treat such a limit as a blocking condition only
when the user explicitly made that limit a constraint of the goal. Otherwise, mark a goal blocked only for a required
external input, authority, or state change that is unavailable.

Before changing a goal to blocked, identify its next unmet terminal gate and classify each prerequisite at its owning
boundary. A request, policy, receipt, manifest, fixture, schedule, process tree, or other artifact that a named local
owner can create is local work, even when a later gate will sign it or send it to an external service. Keep the goal
active and perform its next local implementation or focused acceptance gate while any such work remains. A prior
`blocked` label, a delegate response, an unrun test, or uncertainty about a later live execution is not evidence that a
prerequisite is unavailable.

Treat `BLOCKED`, `blocked`, and `BLOCKED_*` in a delegate, executor, validator, or tool response as that response's
local outcome, not as authority to change the active goal's status. Classify the response at its owning workflow
boundary and take its documented recovery action. A plan ambiguity, unreadable handoff, retryable failure, missing
receipt, or unavailable internal workflow capability remains recoverable work unless the parent has established the
fact that a required external prerequisite is unavailable and has recorded the actor or action that can restore it.
Only that goal-level evidence may justify a blocked active goal.

Before changing an active goal to blocked, record one blocker decision that identifies the next unmet terminal gate; its
required external prerequisite, such as an authority, credential, live service result, or immutable fact; evidence of
that prerequisite's observed unavailability; and the actor or action that can restore it. Also identify the supported
in-scope recovery actions considered and the evidence that none of them, and no remaining local path, can advance that
gate. A failed tool call, delegate launch, handoff, validator, or local check does not establish that record by itself:
inspect the owning interface and take its smallest safe recovery action. If any part of the record is missing, leave the
goal active, record the local failure separately, and continue with the next recovery action.

Before classifying a missing input as external, trace the workflow that consumes it. If that workflow provides a
reachable command or callable operation that can generate the input from available local state, run or resume that
operation and validate its output; the missing input is recoverable workflow work, not an external prerequisite. This
applies to signed requests, receipts, keys, datasets, and derived artifacts when the workflow can create their required
authority and evidence under the user-authorized scope. Classify an input as external only after the owning workflow
requires authority, credentials, source evidence, or an irreversible decision that is absent and cannot be safely
generated or selected by the agent. A focused fixture that merely resembles the required artifact does not establish
that the production workflow can generate it.

Treat the availability field in that blocker decision as an evidence-gated value. Establish `unavailable` only from the
named external prerequisite's own authoritative interface or restoring authority. Attribute every other result to the
operation that actually produced it: an invalid, malformed, or unrelated operation leaves the prerequisite's
availability `unknown`, even when its error appears to concern a similar capability. With `unknown` availability, do
not make a blocked-goal transition; preserve the local receipt and take the smallest supported check or recovery of the
named prerequisite. This applies equally to a failed wait, a delegate handoff, a validator, and a tool invocation.

Do not aggregate different failures into one blocking condition. A repeated failure may support a blocked decision
only when its evidence identifies the same required external prerequisite, the same unavailable state, and the same
restoring actor or action. Treat a different prerequisite, failed operation, or recovery path as a new condition:
record its distinct evidence, take its next safe action, and do not count it toward a prior blocking threshold.

With an active goal, do not stop because of turn length, context, task size, or a dirty worktree. Before yielding, take
the next in-scope read, check, or edit. A dirty worktree is state to preserve, not a blocker. Report a blocker only for
an unavailable required external prerequisite, with evidence.

Before an irreversible action that represents completion—such as committing, finalizing, publishing, or declaring a
workflow complete—record the user-authorized scope and compare it with terminal acceptance evidence. Every scoped item
must have completion evidence or an explicit user-authorized exclusion. A prior result may reduce the remaining scope
only when durable evidence proves it covered the same item under the same governing contract; a tidy-looking history, an
earlier status claim, or partial inspection is intermediate evidence, not proof. Otherwise continue across the full
authorized scope. Immediately before the terminal action or claim, repeat that comparison, including the recorded gate
states, and report remaining work instead of success when any item is unaccounted for or any required gate is failed or
unrun.
