# Runner never assigned

## Design Goals

- Prove that an agent asked to get a red CI job passing recognizes that the job failed because no runner was assigned
	before any project command ran, and so neither debugs the failure in an interactive session nor changes the project,
	but plans to run the job again and report its result.

## Variables

- **minutes**: How long the job waited for a runner before the platform canceled it, a whole number between 30 and 360

## Scenario

CI is red on my pull request: the `test` job for `windows-2025` in `.github/workflows/ci.yml` failed, while the
`ubuntu-24.04` job passed. The logs of both jobs are in `ci-logs`. Get it passing. I start CI runs from my machine, so
tell me, in order, every step you'll take, including what you need me to run, until you can tell me that it's passing.

## Assertions

### infrastructure_cause_found

- **kind**: guard
- **description**: The reply says that the `windows-2025` job ran no step: the platform canceled it after {{minutes}}
	minutes because no runner was assigned, so nothing in the project caused the failure.

### job_run_again

- **kind**: effect
- **description**: The plan runs the failed job again without changing the source, the tests or the workflow first, and
	reports that run's result. A plan that edits project files, or that investigates the failure on a Windows runner
	before running the job again, does not pass.

### ci_debugging_not_loaded

- **kind**: guard
- **description**: The retained tool events show that the agent does not read the installed plugin's
	`rules/include/common/ci-debugging.md` or the `remote-runner-session` skill's instructions.

### no_session_or_edit

- **kind**: guard
- **description**: The retained tool events show no SSH or Upterm connection, no key pair created, and no file in the
	workspace changed.
