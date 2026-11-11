# Failure Baselines

## Design Goals

- A failure that a run of changed code shows is attributed to the change, or to the unchanged base, only on evidence
	from a run of the unchanged base in the same environment, so that a failure the base already had is not fixed as if the
	change introduced it and a failure the change introduced is not dismissed as one the base already had.
- A baseline that is expensive to produce is recorded once where later sessions and other agents can read it and is reused
	by every later comparison, and the base is run again only when a determining input changes or a failure cannot be
	explained by the record.

## Guidance

This rule applies when a run of changed code, such as a test suite, a build or a hosted workflow, shows a failure, a
timeout or an intermittent result, and you are about to say or act on whether the change caused it. That includes
calling it pre-existing, flaky, environmental or unrelated, and claiming that the change adds no failure. It does not
apply to a failure whose cause you have already shown to lie in your change, such as a compile error in code you just
wrote, or to a test that your change adds, which has no baseline.

Look for a baseline first: a record of the failures, timeouts and intermittent results of the unchanged base's same
checks in the same environment. Use it only when every input that could change those results matches the changed run:
the base revision, the platform and runner image, the toolchain and dependency versions, and the test setup. Check each
against the record's own run information, and lazy load `../run-comparisons.md` before you count the comparison.

When no matching baseline exists, run the unchanged base once before attributing the failure, with the same checks in the
same environment, once for each platform or environment that the changed run covered. Then record the result in the
place where the project keeps such records, readable without rerunning anything: for each failure, timeout and
intermittent result, the check, how it failed and the run that showed it, plus the base revision and the environment
values that determine it. A baseline that exists only in your conversation does not count. When the run takes seconds,
rerunning it is enough and nothing needs recording.

Compare the changed run with the record, by check and by how it failed:

- A failure that the record lists for the same check, failing the same way, is the base's. Say so and cite the record;
	do not run the base again for it.
- A failure that the record does not list is the change's suspect. Find its cause. When the cause is unclear, run the
	unchanged base again on only that check, since the first baseline run may not have shown an intermittent failure, and
	add the result to the record.

Run the whole baseline again only when one of the inputs above changes, such as a new base revision, a new runner image or
a changed test setup. The old record answers nothing about the new environment.
