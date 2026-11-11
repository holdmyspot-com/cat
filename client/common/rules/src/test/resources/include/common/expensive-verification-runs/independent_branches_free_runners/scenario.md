# Independent branches on free runners

## Design Goals

- Prove that an agent asked for the CI results of three branches that do not depend on each other, on a repository whose
	runners cost nothing, starts runs for as many branches as the runners' concurrency limit allows before any run
	finishes, rather than finishing one branch's run before it starts the next, queues no run beyond the limit, and does
	not ask the user for permission to use runners that are free.

## Variables

- **failing_test**: The test that fails on `fix-locale`, such as `test_locale_fallback` or `test_currency_rounding`

## Scenario

The branches `fix-dates`, `fix-locale` and `fix-retry` are pushed, and each has a fix that I want checked by CI. They
change different files and do not depend on each other. CI runs on our hosted runners. `sh scripts/ci.sh` starts and
reports CI runs; run it without arguments for its commands. Tell me which of the branches pass CI and why any of them
fails. Do not edit any file in the project.

## Assertions

### runs_overlap

- **kind**: effect
- **description**: The agent starts runs for at least two of the branches while no run has finished, such as by
	starting a second run before it reads a `passed` or `failed` state, rather than waiting for one branch's run to
	finish before it starts another.

### limit_respected

- **kind**: guard
- **description**: No run that the agent starts is reported as `queued`: the agent starts a run only when a runner is
	free, so that it starts the third branch's run after another run has finished.

### no_permission_asked

- **kind**: guard
- **description**: The agent does not ask the user for permission or confirmation before it starts runs, and the final
	reply does not ask whether it may use the runners.

### results_reported

- **kind**: guard
- **description**: The final reply reports that `fix-dates` and `fix-retry` pass and that `fix-locale` fails because
	of `{{failing_test}}`.
