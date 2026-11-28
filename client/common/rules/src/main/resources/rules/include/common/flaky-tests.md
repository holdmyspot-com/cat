# Flaky Tests and Intermittent Reproductions

## Design Goals

- Keep in the suite only tests whose outcome is decided by the code and its inputs, so that a failure always means a
	defect and a pass always means the claimed outcome occurred.
- Confirm the fix of a problem that a test triggers on only some runs with before-and-after evidence whose pass the
	reader can explain, without leaving that test in the suite.
- Leave no claim silently unproven when a flaky test is removed.
- Tell a failure that a change causes from one that was already there by comparing against a reference that differs
	only in that change, and prefer a reference that excludes the agent's own environment as the cause.
<!-- An agent asked for the root cause of an intermittent CI failure reran the old check about 281 times on 21 runners
     after the first few dozen runs showed that it did not fail on demand: 151 tool calls in about 58 minutes. -->
- Bound the runs spent trying to reproduce a failure that occurs on only some runs, and when the bound passes without a
	reproduction, leave its next occurrence observable, its likely cause fixed, and its cause reported as unproven.

## Guidance

A test is flaky when its outcome varies between runs while the code, the test's inputs, the platform, and the
configuration stay the same. A test that fails on every run on one platform and passes on every run on another is not
flaky; the platform decides its outcome, and this rule does not govern it. A randomized test, such as a property-based
test, is reliable only when the seed is its only source of variation, each run records its seed, and a rerun with that
seed gives the same outcome. A new failure that such a test finds is a reproducible defect: fix it and keep the failing
seed as a fixed case. A randomized test that does not meet those conditions is flaky.

When a test that should reproduce a problem triggers it on only some runs, first try to make a reliable test of the
same claim. Find the factor that decides whether the problem occurs, such as thread scheduling, event ordering, the
clock, load, iteration order, or a resource limit, and control it from the test. For example, force the racing
interleaving with latches or an injected executor, pass a fixed clock, supply the elements in each order that matters,
or configure a small resource limit so that exhaustion occurs after a few operations. Keep the reliable test as the
regression check.

If no reliable test can be made, write a temporary test that is more likely to trigger the problem, such as a stress
test that runs the racing operation on many threads for many iterations. Keep it uncommitted in the working tree or in
a scratch location outside the repository. Then:

1. Before the fix, run it and confirm that it triggers the problem. Record the runs and failures.
2. After the fix, run it under the same conditions with enough repetitions or load that a pass is evidence. State why:
	for example, if it failed 12 of 100 runs before the fix, an unfixed defect would pass 100 runs in a row with a
	probability of about 0.000003.
3. Remove the temporary test before committing or handing off the work.
4. Report the runs, failures, and conditions, such as load, thread count, and machine, before and after the fix.

When you decide whether a failure comes from your change or was already there, such as a regression in a fork of an
upstream project, compare the failing run with a reference that differs from it only in the suspected change. Prefer a
reference that someone else already ran in their own environment: reading it costs nothing, and it excludes the
possibility that your environment causes the failure. For a fork, use the same test or job in the upstream project's own
CI logs for the same base commit, or the nearest one. For a branch or pull request, use the base branch's CI history for
the same test. For a dependency, tool, or runner-image upgrade, use that project's own CI or issue tracker for the
version. For a configuration or infrastructure change, use runs from before the change. When no such reference exists
or it does not cover the test, run the reference and the changed version in your environment, such as the unpatched base
commit and the patched version, the old and the new version, or the old and the new configuration. That comparison
isolates the change but does not exclude your environment as the cause. If only the changed version fails, the change is
the likely cause; confirm it with repeated runs when the failure is intermittent. If both fail, the change is not the
cause; look at the environment or at flakiness, as the rest of this rule describes. If neither fails on rerun, the
failure did not reproduce; treat it as possibly flaky, as the rest of this rule describes.

When you decide whether a failure is a flake, rerun the smallest scope that can show it, not the whole workflow: only
the failed jobs, or only the named tests through a stress or repeat input. Before you call the failure a flake, read
the job's log for the retry attempts that it already makes. A test that failed every attempt reproduces under that
job's conditions, so it needs a cause, not a rerun.

Before you rerun a check that fails on only some runs, such as a test, build step or CI job, or run a temporary test
to reproduce such a failure, set a reproduction budget: a number of runs and a time limit. Size it from the failure's
observed rate: at a rate p, about 3/p runs show the failure with 95% probability, for example 60 runs for a failure seen
once in 20. When the budget passes without a reproduction, stop rerunning, then:

1. Make the next failure observable: have the check keep the output that it now hides, such as the failing process's
	error output or log, or a thread dump at a timeout.
2. Fix the likely cause that the evidence you have supports.
3. Report that the cause is unproven and the fix has no test evidence, with the attempts you made and the closest
	evidence you have.

Do not keep a flaky test, whether you wrote it or found it in the existing suite. Make it reliable or remove it.
Retrying it, rerunning it until it passes, and marking it ignored, disabled, or expected to fail are not fixes. When you
remove a flaky test and no reliable test proves its claim, say so in your report and name the claim that is no longer
proven.
