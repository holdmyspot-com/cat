# Execution Lifecycle

## Design Goals

- Preserve ownership of a started external operation until its recorded terminal result establishes whether the
  required work completed, failed, or was interrupted.
- Preserve verified work after an owner interruption by resuming only the claimed work that lacks a recorded terminal
  result when the workflow retains an executable plan.
- Keep a command's outcome observable when a descendant retains its terminal channel after the command itself has
  returned.
- Use a durable-job mechanism only when the execution environment documents a terminal-status contract that can be
  retained and verified.

## Guidance

When a supported execution tool can allocate a PTY or another session-bound channel, use it for an operation that may
outlast one tool result. Immediately retain the returned session identifier in the caller's live workflow state. When
an execution tool yields a live process session instead of a terminal exit result, the caller still owns that process.
Repeatedly wait or poll that same session until it returns a terminal exit result. A per-poll wait limit is a reporting
opportunity, not permission to end the task, declare the process unavailable, or replace it with a detached child.
Before returning control, inspect both the session's terminal result and the workflow's terminal record. A short,
synchronous command that already returns its terminal result does not need a PTY merely to satisfy this rule.

For a long foreground command whose descendant can inherit the tool's terminal channel, create a command-owned terminal
receipt outside that channel. Redirect the command's diagnostic output to a retained temporary file, wait for the
command itself, then atomically write its exit status, completion time, command identity, and diagnostic-file path to
the receipt before the wrapper exits. This prevents a child that merely keeps a terminal descriptor open from making a
completed build look unfinished. The receipt proves only that the named command returned; it does not prove that an
independent child completed. If the command's required outcome includes its whole process tree, the wrapper must wait
for and record that tree's terminal state instead.

If the tool session remains open after a valid receipt, first compare the receipt with the expected command and inspect
the retained diagnostics. Treat the recorded exit status—not the absence of a process with a guessed name—as the
command result. The session may then be closed as a channel-cleanup action, while reporting separately that its
descendant owner was not identified. Without a valid receipt or a terminal tool result, preserve the state as
unresolved and do not call the command successful or failed merely because the original process is no longer listed.

Use a durable-job mechanism only when the execution environment documents submit, status, and terminal-result
operations. Retain the returned job identifier with the workflow record and use the documented terminal status to mark
the work completed, failed, or interrupted. Do not simulate a durable job with a background shell process when the
environment does not guarantee that it outlives the execution session.

When a workflow owner disappears, do not discard a whole recorded run merely because its tool session ended. First use
the workflow's status command. If it reports a recovery action and a retained plan plus unrecorded claimed work, invoke
that recovery action so the workflow records only ownerless workers as interrupted, repeats only the unfinished work,
and keeps every terminal result already recorded. It must refuse to disturb a worker that still has an owner. If the
status instead reports that it cannot resume, let the same recovery action record an interrupted result. The workflow
command, rather than a caller's directory scan or reconstructed arguments, must determine which path applies and retain
the decision it made.

Publish a controller's durable identity, current phase, active workers, and renewable liveness deadline before any
preparatory action that can affect a recorded workflow result, including building, preflighting, materializing input,
calibrating, or launching work. Update that publication at each phase boundary and before a worker claim expires. A
status or recovery command must treat a current controller publication as active ownership, not infer abandonment from
an incomplete directory or missing child process. After the publication expires and the owner is confirmed absent,
terminalize only its ownerless work and resume only unrecorded units under the same retained identity.
