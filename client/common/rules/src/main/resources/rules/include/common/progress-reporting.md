# Long-Running Progress Reporting

## Design Goals

- Let a user follow a multi-minute task through concrete completed and remaining work, using only available reporting
  opportunities and observed current-task evidence to improve estimates.

## Guidance

Before beginning a task expected to run for multiple minutes, divide the remaining work into bounded components and
create one invocation-local, non-committed ETA history file. The workflow that reports progress owns this file. Create
it before the first estimate, keep it available only for the current task, and do not rely on it after the task ends.

An agent can report on a cadence only while it has control. Use an orchestrator heartbeat or monitor when one is
available. Otherwise divide execution into operations that return control no later than the requested cadence and send
the report between them. If an operation cannot yield that often, say before starting it that no timed report can be
sent while it runs, then report immediately when control returns. Do not claim that a prompt alone can wake the agent or
guarantee a wall-clock report.

For every external harness, process, or request, define a completion deadline and a bounded progress probe before
waiting. At each probe, inspect an observable completion artifact or liveness signal. On the deadline, stop waiting,
record the last evidence, and either retry through a defined recovery path or report the blocker. Never turn repeated
polling without new evidence into progress.

Immediately before appending an estimate, read the authoritative current clock and record the returned timestamp
exactly; do not predict, round forward, or manually invent it. Verify that it is not earlier than the preceding history
entry. If the clock or stored history makes that impossible, stop and correct the record before reporting rather than
emitting a future or out-of-order update. Record each estimate with this observed time, completed and total units for
each component, remaining duration in precise minutes, and the observed basis for the estimate. At each later report,
compare the previous forecast with elapsed component work. Revise the estimate from that observed rate and state how
much it changed and why. If there is no prior measurement, say so instead of claiming improved accuracy. Do not use an
uncommitted history file from an earlier run.

When an observable work event occurs, add its completed work, remaining work, failure, or changed estimate to the
pending progress information. Before sending a regular progress report, compare the current clock with the timestamp of
the last regular progress report. If less than one minute has elapsed, retain the pending information and send no
progress report. If one minute or more has elapsed, send one report that combines every pending event. Polling a live
operation without a changed observable state is not a progress event. Use a different cadence only when the user
requests it. A direct answer to a user question and the final result that ends the work are not regular progress
reports.

For a retained background process, record the timestamp of each poll and do not poll it again until one minute has
elapsed. A poll that finds no changed observable state adds nothing to the pending progress information. This applies
only to a live session that needs polling; a short synchronous command that returns its terminal result does not have a
background process to poll.

Format every regular progress report in this order:

- List the components, or a component hierarchy, with one `X/Y` completion count and state per item.
- After the list, write `Current estimated remaining time: about <human-friendly duration>.`
- Then state whether the estimate increased or decreased from the previous report, by a human-friendly duration, and the
  observed reason. For the first estimate, state that no prior estimate exists.

Render a duration under one hour in minutes. At one hour or more, render hours and remaining minutes. Above 24 hours,
also render days, then remaining hours and minutes. Omit a zero-valued smaller unit; for example, write `about 45
minutes`, `about 1 hour 20 minutes`, or `about 1 day 2 hours 5 minutes`. Keep the precise minute count in the history
file so comparison and recalibration do not depend on rounded display text.

For example:

- Conventions audit and commit: 1/1 done
- Source responsibility review: 31/31 done
- Reconstructed topic outputs: 9/31 sealed
- Replay verification and final gates: 0/1 done

Current estimated remaining time: about 1 hour 15 minutes.

This is 10 minutes shorter than the previous estimate because source responsibility review completed without a restart.

Use ordinary task language; do not make the user infer project-specific labels or what each count represents.

Do not invent a percentage, ETA, or reason from an unbounded task or unobserved work. First identify the remaining
components and their completion evidence. For a task too small to need a timed update, report the completed result
instead of creating ETA state.
