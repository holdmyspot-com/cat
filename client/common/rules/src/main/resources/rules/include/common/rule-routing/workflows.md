# Agent and Workflow Rule Routing

## Design Goals

- Route each planning, delegation, handoff, waiting, and workflow-state decision to its focused rule before
	the reader commits to that action.

## Guidance

When applying a skill would cause you to ask for permission or confirmation, pause, leave requested work unfinished, or
diverge from the user's intent, lazy load `../skill-interruption-reporting.md` before taking that action.

Before creating, reviewing, or revising an implementation plan for AI agents to execute, lazy load
`../implementation-planning.md`. Also lazy load it while executing a multi-step plan or goal, before choosing the next
work unit and before starting or extending an investigation, probe, diagnostic, or supporting artifact that no step of
the plan names. A checklist of steps you are about to perform yourself in the current task is neither such a plan nor a
multi-step plan whose next work unit you choose.

Before delegating work to a subagent, or when designing, updating, or executing a prompt-driven workflow that delegates
work, lazy load `../delegation.md`.

Before sending a task, result, correction request, or other information to another agent, or when designing a workflow
whose agents exchange information, lazy load `../agent-handoffs.md`.

When creating, using, or handing off temporary files or directories, lazy load `../temporary-resources.md`.

Before starting a session through CAT's `claude-runner` or `codex-runner` launcher, including from a session that a
runner started, or requesting a session from inside a run through the `request-session` launcher, lazy load
`../harness-runners.md`.

When a task needs a state change that something else produces, such as a hosted build finishing, a background process
exiting or a subagent returning, lazy load `../external-waits.md` before you check that state or continue other work.

When defining or changing a workflow with progress handoff, retry, or resumption, or before dispatching independent
work units to run concurrently, lazy load `../monotonic-progress.md`.
