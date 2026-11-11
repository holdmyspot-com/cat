# Failed job in a multi-job run

## Design Goals

- Prove that an agent that waits for a hosted run with several jobs, where one job fails early and another job is
	still queued for capacity, waits through a check of the per-job states that ends on the first failed job, rather
	than through a command that returns only when the whole run ends, and reports the failure without waiting for the
	queued job.

## Variables

- **failure_after**: The number of seconds after the first status check before the `windows` job fails, a whole number
	between 30 and 90
- **cause**: The failing test, such as `test_path_separators` or `test_line_endings`

## Scenario

Your push of the branch `fix-line-endings` started run 4812 of the CI workflow with three jobs: `linux`, `macos` and
`windows`. `sh scripts/run-jobs.sh` prints each job's state, one per line, as `<job>: queued`, `<job>: running`,
`<job>: passed`, `<job>: failed` or `<job>: cancelled`. `sh scripts/watch-run.sh` prints nothing until the whole run has
ended, then the run's conclusion. `sh scripts/job-log.sh <job>` prints a job's log, `sh scripts/cache-list.sh` lists the
repository's cache entries and `sh scripts/cancel-run.sh` cancels the jobs of the run that have not ended. Tell me how run
4812 went and why any job failed. Do not edit any file in the project.

## Assertions

### wait_ends_on_first_failed_job

- **kind**: effect
- **description**: The agent waits through the plugin's `wait-until` with a `--check` command that reads the per-job
	states by running `sh scripts/run-jobs.sh`, and that exits with 1 as soon as any job has failed. It does not wait
	through a `--watch` command or a call that runs `sh scripts/watch-run.sh`, and it does not run the status command
	itself at intervals, apart from one run of the `--check` command before the wait starts.

### failure_reported_before_run_ends

- **kind**: effect
- **description**: The final reply reports the `windows` job's failure, and the agent sends it without waiting for the
	`macos` job, which stays queued for much longer than the `windows` job runs, to start or finish.

### cause_reported

- **kind**: guard
- **description**: The final reply names `{{cause}}` as the cause of the `windows` job's failure.

### rest_of_run_cancelled_with_cache_check

- **kind**: effect
- **description**: After the `windows` job fails, the agent runs `sh scripts/cache-list.sh`, finds that the `linux` job's
	cache key `deps-linux-7f3a9c` already exists, and runs `sh scripts/cancel-run.sh` before the final reply, without
	waiting for the `linux` job to end.
