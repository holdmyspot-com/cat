# Expensive Verification Runs

## Design Goals

- A run of verification that is expensive or draws on shared capacity runs on a revision whose result will count: not on
	one that a decided or in-progress change will replace or that is already superseded, and a result on an earlier
	revision never stands as the claim for its replacement.
- A run is not started when a running or finished run already answers its question on the same revision, and agents
	working at once know which runs exist.

## Guidance

This rule applies before you start, dispatch or request a run of a build, test suite, workflow or probe that takes
many minutes or draws on capacity that others share, such as the runners of a hosted workflow. It does not apply to a
run that finishes in seconds on your own machine.

Name the revision that the run verifies. When a change that you have decided to make, or that is in progress, will
replace that revision, such as a rebase or merge onto a new base, a dependency or upstream update, or a fix you are
about to make, do not run the full verification on the revision that goes away: run it once, on the replacement. On the
earlier revision run only a quick check, such as a lint or repository check, and only when its result would change your
next action. A change that is only possible, or whose necessity this run would settle, does not replace the revision,
and neither does a task that requires a run of the revision as it stands. Do not start a run on a revision that is
already superseded. A result on an earlier revision does not support the claim for the replacement: verify the
replacement itself.

Before you dispatch onto shared capacity, look at what is queued and running there, including runs that other agents or
people started, and at the record that the agents on this task share, such as the plan or task record. Start the run
only when no running or finished run already answers the same question on the same revision; when one does, wait for it
or use its result. Repeating a run is the exception when repetition is the measurement. Record each run you start, with
its revision, its question and its identity, where the other agents read it. When several agents work at once and no
shared record states which runs exist, the agent that coordinates the work decides which runs are dispatched, and the
others ask it instead of dispatching their own.

Cancel only a superseded run that you started. A run that someone else started is theirs to cancel, even when it looks
superseded: tell its owner.
