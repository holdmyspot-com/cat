# CI and Remote-Failure Debugging

## Design Goals

- Select the fastest available reproduction boundary that can distinguish a suspected failure of the project's build,
	tests or scripts on another machine, such as CI, a hosted workflow or a remote build or test host, without treating
	a locally different execution as evidence of the remote result.
- When a failure does not reproduce locally, its cause is found and each fix is tried in an interactive session on a
	runner of the same kind before any fix attempt is pushed, so that a hosted run confirms a fix that already passed
	there instead of testing a guess.
<!-- A release build stopped at its first failing module on Windows, so three fix pushes, each followed by a run on all
     five platforms, each showed only the next Windows failure while the other four platforms passed again. -->
- Before a fix is pushed, every check of the failed job has passed where the failure was reproduced, and the first run
	of those checks there does not stop at a failure, so that the confirming hosted run does not show a failure that an
	earlier failure hid.
- A session or diagnostic run covers only the failing platforms and jobs and the jobs whose output they consume, and
	the complete required workflow runs once, after the fix passes, as the acceptance gate.
- Preserve hosted acceptance evidence when the required platform, identity, artifact, or service boundary is unavailable
	locally or is part of the required outcome.

## Guidance

When fixing a failure of the project's build, tests or scripts on another machine, identify the failed step's executed
command, determining inputs, consumed artifact, harness configuration, and platform before choosing where to
investigate. Read the retained log, receipt, and artifacts first, and preserve them.

Reproduce the failure locally first. If a supported local invocation can exercise that same relevant boundary from
retained or reproducible inputs, run it locally before dispatching a replacement hosted run. Use its result to narrow
the cause and repair the responsible source; a passing local run does not establish that a hosted-only boundary passed.

If the local run does not reproduce the failure, or the local machine cannot run the failing platform, such as a
Windows-only failure on a Linux machine, open an interactive session on a runner of the same kind through the
`cat:remote-runner-session` skill before you push any fix attempt. Reproduce the failure there, find its cause, and
try each fix there until it passes. Every push and hosted run costs minutes and API calls and tests only one guess, so
do not use a hosted run to find out whether a guess works. Do not substitute a similar local command for that session.

Wherever you reproduce the failure, run every check of the failed job on that platform, meaning the build, test and
other verification commands it runs and not only the one that failed, in a mode that continues after a failure, such as
a build tool's fail-at-end or keep-going option. A job that stops at its first failing module, test or step hides the
failures after it, so its log does not show that the rest pass. Fix every failure that run shows, narrowing the runs
that find a cause to that failure, and push only after all of the job's checks pass there: they are the boundary that
the confirming run tests on that platform. Where a check cannot continue after a failure, rerun it after each fix. Run
the session, and any diagnostic run, only for the failing platforms and jobs and the jobs whose output they consume.

When the failure depends on a hosted identity or permission, protected input, attestation, live service, or other
boundary that neither the local machine nor a session on a runner of the same kind exercises, identify the smallest
supported hosted check that can distinguish the remaining cause and run only that check.

After every check of the failed job passes locally or in the session, push the fix and run the complete required
hosted workflow once, on every platform, whenever its CI-specific result remains an acceptance gate. If that run
fails, return to the session instead of pushing another guess. Record local reproduction, session work, and hosted
verification as separate evidence, each scoped to the boundary it actually exercised.

When debugging a GitHub Actions workflow, lazy load `ci-debugging/github-actions.md` before dispatching a diagnostic
run or changing its job selection.
