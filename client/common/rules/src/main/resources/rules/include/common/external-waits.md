# External Waits

## Design Goals

- When the agent's next action needs a state change that something else produces, such as a hosted build finishing, a
  background process exiting or a subagent returning, the agent keeps doing in-scope work that does not depend on that
  change, then blocks until the change or a stated deadline occurs, instead of checking the state or reporting progress
  at intervals.
- A wait script that exits at its deadline ends the wait with the last state it observed, while one that has not exited
  five minutes after its deadline is terminated and started again, so a stuck script neither holds the agent
  indefinitely nor ends the wait before the operation's result or deadline.
- When any of several state changes would let the agent continue, the agent acts on the first one to occur without
  waiting for the others.

## Guidance

This rule applies when a task action needs a state change that you do not produce yourself. It does not apply to a
command that returns its result within one tool call, or to work that you perform yourself.

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

Start the script so that the harness notifies you when it exits. Then, whether you wait on a script or on a wait
operation, keep doing in-scope work that does not depend on the awaited change, and block on the wait once no such work
remains. When the harness offers no exit notification, wait on the same process again each time a tool call's wait
limit passes, with no message in between.

Before blocking, send one line that names what you are waiting on and when the deadline passes. Then send nothing about
the wait until it ends. Do not run the operation's status command yourself while the script runs, and do not send
progress reports. When the user asks a question during the wait, answer it, then resume the same wait.

When the script exits at its deadline, the wait ends: report the last state that it printed.

Give your own wait a limit five minutes longer than the script's deadline. If that limit passes and the script has not
exited, terminate the script by its process ID, start the same script again and block on it as before. If the operation
finished in the meantime, the new run exits at once with its result; otherwise it waits until its own deadline.

When any of several state changes would let you continue, start one wait script or wait operation for each and block
until the first one ends. Act on its result. If that result makes the other waits unnecessary, terminate them by their
process IDs; otherwise, block on the rest again.
