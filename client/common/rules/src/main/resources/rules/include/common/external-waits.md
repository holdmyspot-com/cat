# External Waits

## Design Goals

- When the agent's next action needs a state change that something else produces, such as a hosted build finishing, a
  background process exiting or a subagent returning, the agent blocks until that change or a stated deadline occurs,
  instead of checking the state or reporting progress at intervals.
- A wait that outlives its deadline ends: the agent stops waiting, stops what it was waiting on through, and reports the
  last state observed.
- When any of several state changes would let the agent continue, the agent acts on the first one to occur without
  waiting for the others.

## Guidance

This rule applies when your next task action needs a state change that you do not produce yourself and you have no
other in-scope work to do until it occurs. It does not apply to a command that returns its result within one tool call,
or to work that you perform yourself.

When the state change is a process exiting or a subagent returning, and that process or subagent offers a documented
wait operation, block on that operation. Otherwise, such as for a hosted build, a deployment or a review, write a wait
script in a temporary directory:

- The script observes the state through the operation's documented status interface, such as `gh run watch` for a
  GitHub Actions run, and exits once the awaited state is reached, printing that state and returning the operation's
  result as its exit status.
- The script takes a deadline: the operation's own time limit, such as a workflow job's `timeout-minutes`, plus a
  margin. When the operation declares no limit, base the deadline on its observed or documented duration and state that
  basis. When the deadline passes, the script prints the last state it observed and exits with a status that no other
  outcome uses, such as 124.

Before blocking, send one line that names what you are waiting on and when the deadline passes. Then send nothing until
the wait ends. Do not run the operation's status command yourself while the script runs, and do not send progress
reports. When the user asks a question during the wait, answer it, then resume the same wait.

Block on the script's exit. When one tool call cannot last until the deadline, start the script so that the harness
notifies you when it exits, and block on that notification. When the harness offers no such notification, wait on the
same process again each time a tool call's wait limit passes, with no message in between.

Give your own wait a limit slightly longer than the script's deadline. If that limit passes and the script has not
exited, stop waiting, terminate the script by its process ID, and report the last state that it printed.

When any of several state changes would let you continue, start one wait script or wait operation for each and block
until the first one ends. Act on its result. If that result makes the other waits unnecessary, terminate them by their
process IDs; otherwise, block on the rest again.
