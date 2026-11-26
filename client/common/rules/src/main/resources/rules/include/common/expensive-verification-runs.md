# Expensive Verification Runs

## Design Goals

- An expensive or shared-capacity run verifies a revision whose result will count, and a gate that requires evidence
	for a specific revision gets it from that revision.
- A passing result prevents a repeat while it still answers the verification question, unless a new failure warrants
	rechecking the behavior or the verification requires a fresh run. Agents working at once know which runs exist and
	wait for an in-progress run that already answers the question.
- Before committing resources to an expensive run, confirm its prerequisites and any claimed comparability between
	targets with the earliest inexpensive observations the workflow supports.
- An expensive run is started with the inputs that its question needs, and a wrong input is found when the run starts
	instead of when it ends.
- Spend a slow or scarce target's run only when it is likely to answer for the revision that ships: after the other
	targets pass while the workflow is being changed or repaired, and without waiting for them once the round is
	expected to pass.
- While runs are in progress on shared or hosted runners, independent work that needs a runner starts at once, up to the
	provider's concurrency limit, instead of waiting behind one run, when the extra runner time is free. Extra runs that
	would cost money wait for the user's permission, and no run is started only to fill the limit.
- A run that a newer run supersedes stops consuming time and resources once nothing needs its result, when the agent
	started it, while a run that someone else started is left to its owner.
<!-- A long end-to-end evaluation that had reached certification was discarded because a new release superseded the
     build it ran on, although none of that release's fixes affected it. -->
- A long evaluation that runs on one build in rounds is not voided mid-round by a new release of fixes that it does not
	need: those fixes ship together between rounds, and only a fix that the running round needs ships during it.
- A run in progress that a change makes unusable, such as a new build, release or input that it does not use, does not
	keep running only because it has started: when stopping it is cheap, it is stopped, what it showed is recorded and
	it starts again on the new version, unless a reason that the user is told, such as diagnostic value, keeps it.
- A run in which a job has failed keeps using shared capacity only while the rest of the run produces something that
	the agent needs, such as a cache that does not yet exist, and is cancelled at once otherwise, when the agent started
	it.

## Guidance

This rule applies before preparing, starting, dispatching or requesting a run of a build, test suite, workflow or probe
whose setup or execution takes many minutes or draws on shared capacity, such as the runners of a hosted workflow.
It also applies when a job of such a run that you started has failed while other jobs are queued or running, when you
have other work that needs a runner while such a run is in progress, before you publish a new build while such a run
uses the current one, and when a change lands while such a run that you started is in progress.

Before repeating a slow or expensive check, look for a recorded pass. Reuse it when the inputs and environment that
affect the result still match and the check covers the current question. Start a new run only when a new failure
points to the behavior it checks, the question still needs evidence that the earlier result cannot provide, or the
verification requires a fresh run, such as when repetition is the measurement.

Before expensive setup or dispatch, identify which prerequisites could fail early and invalidate the result. Use
the smallest supported check that observes the relevant boundary in the environment that will run the work. For
example, confirm which executable and version the launcher actually selects, that the request reaches the intended
service, or that the test process can use a required directory or acquire its lock. When the test depends on a
provider response and a cheaper check cannot confirm it, use the smallest authorized request through the same
client and configured route. If the check needs some setup, run it as soon as that setup makes the boundary
available. Reuse an observation while the inputs and environment that it covers still match. Resolve a failed
prerequisite before spending on work that needs it.

When an expensive suite claims to test the same contract across targets, use a shared definition for the cases, expected
outcomes, and assertions. Target setup can adapt how it supplies inputs or observes results while preserving that
contract. Before running a pilot or the full suite, confirm what each target will actually use. If the workflow
materializes something only during execution, check it at the first supported point it can be observed. Resolve any
mismatch before collecting comparable results. Verify any boundary the inexpensive check cannot exercise in the required
run. Report a target whose required observation is unavailable as unverified.

Before you dispatch a long or expensive run, check each input you pass against what the run is meant to show, such as a
dry-run flag, the target ref and the platform selection. After it starts, read the inputs back from the run's recorded
parameters and cancel it at once if one is wrong.

Name the revision that the run verifies. When a change that you have decided to make, or that is in progress, will
replace that revision, such as a rebase or merge onto a new base, a dependency or upstream update, or a fix you are
about to make, do not run the full verification on the revision that goes away: run it once, on the replacement. On the
earlier revision run only a quick check, such as a lint or repository check, and only when its result would change your
next action. A change that is only possible, or whose necessity this run would settle, does not replace the revision,
and neither does a task that requires a run of the revision as it stands. Do not start a run on a revision that is
already superseded. Verify the replacement itself for a gate that requires evidence for that revision.

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
only when no running or finished run already provides the evidence you need; when one does, wait for it or use its
result. Record each run you start, with its revision, its question and its identity, where the other agents read it.
When several agents work at once and no shared record states which runs exist, the agent that coordinates the work
decides which runs are dispatched, and the others ask it instead of dispatching their own.

When a newer run supersedes one that you started, such as a rerun after a fix, cancel the earlier run once nothing needs
its result, neither the current diagnosis nor other work. Cancel only a superseded run that you started. A run that
someone else started is theirs to cancel, even when it looks superseded: tell its owner.

While a long evaluation runs on one build in rounds, such as an end-to-end evaluation of an installed release, collect
the fixes that the evaluation does not need and publish them together after the current round ends and before the next
starts. Publish a new build during a round only for a fix without which that round's result cannot count, such as a
defect that the round fails on: a new build replaces the one whose result the round would establish.

When a change lands while a run that you started is in progress and the run does not use it, such as a new build,
release or input, decide at once whether the run's result still counts. Being already in progress is not a reason to
keep it. When the result no longer counts and stopping the run is cheap, stop it, record what it has shown, and start it
again on the new version. Keep it running only for a stated reason, such as its diagnostic value, and tell the user that
reason.

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

When a run is in progress and other work of the task also needs a runner, such as a fix for a second failure on another
branch, an unrelated verification, or a reproduction on a hosted worker, start every such piece that does not depend on
another run's result, up to the provider's concurrency limit, instead of serializing them behind that run. Do independent
local work in the meantime. Work that needs another run's result waits for it, such as a verification of a revision that
a fix in progress will replace. This section never makes a run necessary: each run you start still passes the tests
above, including that no running or finished run already provides the evidence you need.

Find the concurrency limit in the provider's documentation or in the account's or plan's settings. When it is not
stated, infer it from how many of the runs that you started run at once while the rest queue. Start no more runs than
fill it, so that they do not queue ahead of the runs of others.

Fill the limit without asking when the extra runner time is free: included minutes, free runners for public
repositories, or self-hosted runners at no extra cost. When running them concurrently would use time that costs money,
such as billed minutes or paid larger runners, ask the user for permission before you start those runs, stating what
would run and the expected cost. When you cannot tell whether the runs are billed, treat them as costing money and ask.
While the question is open, continue the independent work that needs no paid run.
