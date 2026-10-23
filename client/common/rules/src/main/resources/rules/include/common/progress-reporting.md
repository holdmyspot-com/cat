# Long-Running Progress Reporting

## Design Goals

- Let a user follow a multi-minute task through concrete completed and remaining work, using only available reporting
	opportunities and observed current-task evidence to improve estimates.
- Avoid repetitive unchanged-state updates while keeping the work that the agent performs visible and promptly
	reporting a meaningful state change, and send no regular report while the agent waits for a state change that
	something else produces.

## Guidance

Before beginning a task expected to run for multiple minutes, divide the remaining work into bounded components and
create one invocation-local, non-committed ETA history file. The workflow that reports progress owns this file. Create
it before the first estimate, keep it available only for the current task, and do not rely on it after the task ends.

An agent can report on a cadence only while it has control. Use an orchestrator heartbeat or monitor when one is
available. Otherwise divide execution into operations that return control no later than the requested cadence and send
the report between them. If an operation cannot yield that often, say before starting it that no timed report can be
sent while it runs, then report immediately when control returns. Do not claim that a prompt alone can wake the agent or
guarantee a wall-clock report. Report a meaningful state change, such as a completed or failed component or a revised
estimate, at the first reporting opportunity after it occurs. Do not send a regular report that repeats the previous
report's counts, states, and estimate unchanged.

This rule covers work that you perform yourself. When the work also needs a state change that something else
produces, such as a hosted build finishing, a background process exiting or a subagent returning, lazy load
`external-waits.md` and send no regular report about that wait.

Immediately before appending an estimate, read the authoritative current clock and record the returned timestamp
exactly; do not predict, round forward, or manually invent it. Verify that it is not earlier than the preceding history
entry. If the clock or stored history makes that impossible, stop and correct the record before reporting rather than
emitting a future or out-of-order update. Record each estimate with this observed time, completed and total units for
each component, remaining duration in precise minutes, and the observed basis for the estimate. At each later report,
compare the previous forecast with elapsed component work. Revise the estimate from that observed rate and state how
much it changed and why. If there is no prior measurement, say so instead of claiming improved accuracy. Do not use an
uncommitted history file from an earlier run.

A direct answer to a user question and the final result that ends the work are not regular status reports.

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
