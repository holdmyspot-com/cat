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
when the user explicitly made that limit a constraint of the goal.

Before deciding whether a goal is blocked, reporting that work is blocked, or classifying a missing input as external;
before stopping work on an active goal because a tool call, delegate, handoff, validator, check, or prerequisite failed
or seems unavailable; and when a delegate, executor, validator, or tool response is labelled `BLOCKED`, `blocked`, or
`BLOCKED_*`, lazy load `../include/common/intermediate-vs-terminal-goals/blocked-status.md`.

With an active goal, do not stop because of turn length, context, task size, or a dirty worktree. Before yielding, take
the next in-scope read, check, or edit. A dirty worktree is state to preserve, not a blocker.

Before an irreversible action that represents completion—such as committing, finalizing, publishing, or declaring a
workflow complete—record the user-authorized scope and compare it with terminal acceptance evidence. Every scoped item
must have completion evidence or an explicit user-authorized exclusion. A prior result may reduce the remaining scope
only when durable evidence proves it covered the same item under the same governing contract; a tidy-looking history, an
earlier status claim, or partial inspection is intermediate evidence, not proof. Otherwise continue across the full
authorized scope. Immediately before the terminal action or claim, repeat that comparison, including the recorded gate
states, and report remaining work instead of success when any item is unaccounted for or any required gate is failed or
unrun.
