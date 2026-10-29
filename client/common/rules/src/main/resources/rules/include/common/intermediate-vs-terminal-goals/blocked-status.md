# Intermediate versus Terminal Goals: Blocked Status

## Design Goals

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

Except for an executor limit that the user explicitly made a constraint of the goal, mark a goal blocked only for a
required external input, authority, or state change that is unavailable. Report a blocker only for an unavailable
required external prerequisite, with evidence.

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
