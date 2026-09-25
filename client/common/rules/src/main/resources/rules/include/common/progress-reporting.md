# Progress Reporting

## Design Goals

- Let a user follow a multi-minute task through concrete completed and remaining work, using only available reporting
  opportunities and observed current-task evidence to improve estimates.
- Avoid repetitive unchanged-state updates while keeping any observable long-running operation visible and promptly
  reporting a meaningful state change.
<!-- A percentage-of-plan scenario expected the completed readiness packages to count, but supplied no plan inventory
     and had no route for a completion question outside a long-running operation. -->
- Let a user asking how much of a plan is complete distinguish verified work from terminal acceptance and receive an
  overall percentage only when a complete, non-overlapping inventory supports it.

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

For each live external operation, retain a state fingerprint containing its identity, liveness or terminal state, and
every observed completion artifact, phase, completed unit, failure, or estimate that can change the user's next
decision. This includes a retained process or subagent, a durable job, and an externally hosted workflow such as a
GitHub Actions run. For the latter, use its documented status endpoint or event stream; do not infer status from a
browser page, elapsed time, or an unrelated job.

Before scheduling the first delay for a live operation, establish one current state fingerprint from its documented
status or observation boundary. Reuse a complete fingerprint already returned by its start or submission result;
otherwise perform one initial status observation immediately. If that observation is terminal, report it and stop
without waiting. The initial observation establishes the comparison baseline; it is not a scheduled progress probe or
evidence of progress.

Start the next probe and regular-status delay at 30 seconds. At a probe, compare the current fingerprint with the
preceding one. If it changed, report the changed facts, add completed work, remaining work, failure, or changed estimate
to the pending progress information, and reset the next delay to 30 seconds. If it is unchanged, send only a concise
unchanged-state status when the scheduled report is due, then double the next delay. Cap the delay at 15 minutes: the
sequence is 30 seconds, 1 minute, 2 minutes, 4 minutes, 8 minutes, then 15 minutes for every later unchanged
observation. A liveness-only observation is not progress and must not change component counts, percentages, or an ETA.

Use the same current-state observation for the scheduled probe and status report; do not poll merely to produce a
message. Record the observed timestamp, state fingerprint, chosen delay, and next scheduled probe in the invocation's
ETA history. A direct answer to a user question and the final result that ends the work are not regular status reports.
The user may request a different cadence; otherwise this backoff is mandatory.

For a retained process or subagent, block on its documented wait operation until the next scheduled probe or until it
returns a state change, then apply the state-fingerprint decision above. For an operation whose interface requires
polling, such as a GitHub Actions run, do not call its documented status operation before that scheduled probe. This
does not apply to a short synchronous command that already returned its terminal result.

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

When a user asks how much of a plan is complete and a plan-level ledger marks components complete only after their
terminal acceptance, do not treat that terminal status as a binary measure of all implementation work. First inventory
the requested scope at one explicit granularity: terminal components, or non-overlapping implementation work packages
whose receipts establish their completed behavior. Record the denominator, the evidence for every counted completed
unit, and remaining units at that same granularity. Do not count a parent component and one of its child work packages
in the same percentage.

When the available evidence establishes both a terminal-acceptance view and completed verified child work, report the
two views separately. For example, state the passed terminal components out of all terminal components, then the
passed work packages out of the fully inventoried current component. Do not discard verified child work merely because
its parent remains partial, and do not call that child evidence terminal acceptance. Report one overall percentage only
when a complete, non-overlapping inventory at that percentage's stated granularity exists. Otherwise report the
separate counts and say that the overall percentage is unknown; do not substitute a percentage calculated only from
terminal rows.
