# External Waits

## Design Goals

- When a task needs a state change that something else produces, such as a hosted build finishing, a background
  process exiting or a subagent returning, one wait per change, started as soon as the change is needed, is the only
  check of whether it has occurred. The agent keeps doing in-scope work that does not depend on the change and blocks
  on the wait once that work runs out, instead of checking the state or reporting progress at intervals.
- While the agent waits with no other work, it wakes only when a wait limit passes or the awaited change occurs.
- A query for a fact other than whether the change has occurred, such as a failed job's log, stays available whenever
  the agent's work needs it.
- A wait script that exits at its deadline ends the wait with the last state it observed, while one that has not exited
  five minutes after its deadline is terminated and started again, so a stuck script neither holds the agent
  indefinitely nor ends the wait before the operation's result or deadline.
- When any of several state changes would let the agent continue, the agent acts on the first one to occur without
  waiting for the others.

## Guidance

This rule applies as soon as a task needs a state change that you do not produce yourself, including while other
in-scope work remains. It does not apply to a command that returns its result within one tool call, or to work that
you perform yourself.

When the state change is a process exiting or a subagent returning, and that process or subagent offers a documented
wait operation, wait on that operation. Otherwise, such as for a hosted build, a deployment or a review, write a wait
script in a temporary directory:

- The script observes the state through the operation's documented status interface, such as `gh run watch` for a
  GitHub Actions run, and exits once the awaited state is reached, printing that state and returning the operation's
  result as its exit status.
- The script takes a deadline: the operation's own time limit, such as a workflow job's `timeout-minutes`, plus a
  margin. When the operation declares no limit, base the deadline on its observed or documented duration and state that
  basis. When the deadline passes, the script prints the last state it observed and exits with a status that no other
  outcome uses, such as 124.

Start the wait before any other work. Start the script in the background: as a process whose exit the harness reports
to you, or else as one that you can later wait on through the harness's wait operation. Until the wait ends, it is the
only check of whether the change has occurred: do not check completion yourself, whether through the status command,
the process's output, another script, or a check between other tasks. Keep doing in-scope work that does not depend
on the awaited change, and block on the wait once no such work remains. Block with the longest limit that the wait
operation and your other instructions allow: a shorter limit adds wakeups without telling you anything sooner,
because the wait returns as soon as the process exits. When that limit passes first, wait on the same process again
with the same limit, with no message or other tool call in between. Such a continuation is part of the one wait, not a
completion check.

A query for a different fact is not a completion check, such as a failed job's log or the details of a step that your
current work needs; run it when that work needs it. When the wait ends without the operation's result, such as when the
script fails or exits with an unexpected status, one status query to establish the current state is part of handling
that outcome.

Before blocking, send one line that names what you are waiting on and when the deadline passes. Then send nothing about
the wait until it ends, and send no progress reports. When the user asks a question during the wait, answer it, then
resume the same wait.

When the script exits at its deadline, the wait ends: report the last state that it printed.

Give your own wait a limit five minutes longer than the script's deadline. If that limit passes and the script has not
exited, terminate the script by its process ID, start the same script again and block on it as before. If the operation
finished in the meantime, the new run exits at once with its result; otherwise it waits until its own deadline.

When any of several state changes would let you continue, wait for whichever comes first through one wait. If the
harness reports each process's exit to you, or its wait operation accepts several processes, start one wait script or
wait operation for each and block until the first one ends. Otherwise, start one script that launches each wait
script, exits as soon as the first of them exits, prints which one ended and what it printed, and terminates the
others, for example with Bash's `wait -n`. Act on that result. If you still need one of the other changes, start a new
such script for the waits that remain.
