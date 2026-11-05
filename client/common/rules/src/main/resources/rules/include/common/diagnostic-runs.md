# Diagnostic Runs

## Design Goals

- When an operation that is expensive to run in full has failed, each further run made to find the cause can tell
	apart explanations that are still open, and runs only the scope that the suspected mechanism needs, so that every run
	produces new evidence at the smallest cost that can produce it.
- A diagnostic run ends once it has produced the evidence it was started for, and a run that a newer run replaces
	stops consuming time and resources once nothing needs its result, while an acceptance run still runs to completion.

## Guidance

This rule applies before you start another run of an operation that failed and that is expensive to run in full,
because a full run takes many minutes or consumes limited or paid resources, such as a hosted build, a long local build,
or a long probe. It does not apply to an operation that is cheap to run in full, such as a unit test that finishes in
seconds.

Before the run, name the explanations of the failure that are still open and the evidence that would tell them apart.
Choose a run whose result would rule out at least one of them. A run that can only show the same failure again leaves
every explanation open: for example, a third full release run that can only stall where the first two stalled.

Run the smallest scope that exercises the suspected mechanism, such as one test in one module instead of the whole
build, or a ten-minute reproduction of a suspected stall instead of the full release. Repeat the full scope only in
these cases:

- The full scope is the boundary under test, such as a failure that a smaller scope did not reproduce.
- Repetition is the measurement, such as the failure rate of a failure that occurs on only some runs.

Set the run's time limit from the evidence it is to produce, not from the time limit of the acceptance run. For
example, when a stall shows within ten minutes of a step's start, limit the run to that step's start plus ten minutes
and a margin, not to the full run's two hours. Stop the run as soon as that evidence exists, together with any watchdog
or wait that you started for it, even when the run could continue. An acceptance run is different: its evidence is its
complete result, so let it run to completion.

When you start a run that replaces an earlier one, such as a rerun after a fix, cancel the earlier run once nothing
needs its result, neither the current diagnosis nor other work.
