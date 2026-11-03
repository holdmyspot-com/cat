# Flaky Tests and Intermittent Reproductions

## Design Goals

- Keep in the suite only tests whose outcome is decided by the code and its inputs, so that a failure always means a
	defect and a pass always means the claimed outcome occurred.
- Confirm the fix of a problem that a test triggers on only some runs with before-and-after evidence whose pass the
	reader can explain, without leaving that test in the suite.
- Leave no claim silently unproven when a flaky test is removed.

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

If neither a reliable test nor a temporary test triggers the problem, report that the fix has no test evidence, the
attempts you made, and the closest evidence you have.

Do not keep a flaky test, whether you wrote it or found it in the existing suite. Make it reliable or remove it.
Retrying it, rerunning it until it passes, and marking it ignored, disabled, or expected to fail are not fixes. When you
remove a flaky test and no reliable test proves its claim, say so in your report and name the claim that is no longer
proven.
