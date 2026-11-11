# Expensive Verification Runs

## Design Goals

- A run of verification that is expensive or draws on shared capacity runs on a revision whose result will count: not on
	one that a decided or in-progress change will replace or that is already superseded, and a result on an earlier
	revision never stands as the claim for its replacement.
- A run is not started when a running or finished run already answers its question on the same revision, and agents
	working at once know which runs exist.
- Spend a slow or scarce target's run only when it is likely to answer for the revision that ships: after the other
	targets pass while the workflow is being changed or repaired, and without waiting for them once the round is
	expected to pass.
- A run that a newer run supersedes stops consuming time and resources once nothing needs its result, when the agent
	started it, while a run that someone else started is left to its owner.
- A run in which a job has failed keeps using shared capacity only while the rest of the run produces something that
	the agent needs, such as a cache that does not yet exist, and is cancelled at once otherwise, when the agent started
	it.

## Guidance

This rule applies before you start, dispatch or request a run of a build, test suite, workflow or probe that takes
many minutes or draws on capacity that others share, such as the runners of a hosted workflow, and when a job of such a
run that you started has failed while other jobs of it are queued or running. It does not apply to a run that finishes
in seconds on your own machine.

Name the revision that the run verifies. When a change that you have decided to make, or that is in progress, will
replace that revision, such as a rebase or merge onto a new base, a dependency or upstream update, or a fix you are
about to make, do not run the full verification on the revision that goes away: run it once, on the replacement. On the
earlier revision run only a quick check, such as a lint or repository check, and only when its result would change your
next action. A change that is only possible, or whose necessity this run would settle, does not replace the revision,
and neither does a task that requires a run of the revision as it stands. Do not start a run on a revision that is
already superseded. A result on an earlier revision does not support the claim for the replacement: verify the
replacement itself.

When the build or workflow must pass on several targets, such as platforms, runners or jobs, and one target is much
slower or has scarcer capacity than the others, choose when to start it from whether a failure on another target is
likely:

- While you introduce, change or repair the workflow, or iterate on a fix, a failure elsewhere is likely and would
	replace the revision, so a slow or scarce run started earlier would answer for a revision that no longer exists. Start
	that target last, after the other targets pass on the same revision.
- Once the workflow is stable and the round is expected to pass, such as the acceptance run on the revision that ships,
	start that target first or in parallel with the others: it is probably the critical path, and starting it last only
	lengthens the wait.

The ordering decides when to start each target. It never removes a target that the acceptance gate requires.

Before you dispatch onto shared capacity, look at what is queued and running there, including runs that other agents or
people started, and at the record that the agents on this task share, such as the plan or task record. Start the run
only when no running or finished run already answers the same question on the same revision; when one does, wait for it
or use its result. Repeating a run is the exception when repetition is the measurement. Record each run you start, with
its revision, its question and its identity, where the other agents read it. When several agents work at once and no
shared record states which runs exist, the agent that coordinates the work decides which runs are dispatched, and the
others ask it instead of dispatching their own.

When a newer run supersedes one that you started, such as a rerun after a fix, cancel the earlier run once nothing needs
its result, neither the current diagnosis nor other work. Cancel only a superseded run that you started. A run that
someone else started is theirs to cancel, even when it looks superseded: tell its owner.

When a job of a run that you started has failed and the run has other jobs that are queued or running, decide as soon
as you know which job failed whether the rest of the run has value, because a run that is cancelled sooner frees shared
capacity sooner. The fix will replace the revision, so the rest of the run answers for a revision that goes away; keep
it running only when it produces something that you need:

- A cache that a running or queued job will save, whose key does not yet exist. Take the key from the job's cache step
	and list the repository's cache entries for it, in the scope that the next run restores from, such as the branch
	that you push or the default branch. When an entry with that key already exists, the cache is not needed. When the
	key depends on content that your fix changes, such as a hash of a dependency file, the next run uses a different key,
	and this run's cache is not needed either.
- Results of other jobs that would reveal failures that the next run would otherwise need a further round to show, such
	as the same step on another platform that you have not reproduced. A job that runs steps that your fix cannot
	affect, whose results you already have from an earlier run on the same revision, or that is still queued for
	capacity and may not start for a long time, reveals nothing new.
- A worker that an open interactive session of yours still uses.

Neither a request to wait for the run to finish nor the wish to report every job's result is a reason to keep it: the
failed job already shows that this revision will be replaced. When none of the reasons above applies, cancel the run now
through the hosting service, without waiting for the other jobs to end, and record that you cancelled it and why. A run
that someone else started is theirs to cancel, as above. When you keep the run, record what it is kept for, and cancel
it once that result exists or the next run supersedes it.
