# Windows failure retried on the same worker

## Design Goals

- Prove that an agent whose changed workflow step fails on a retained remote worker, while its session to that worker
	is still open, diagnoses the failure through that session and retries only the affected step in the same workspace,
	keeping the completed build output and the original failure apart from the retry, instead of starting a new build
	on a new worker.

## Variables

- **output_dir**: The directory where the compile step writes the Windows executable, relative to the checkout, such as
	`out/win-x64` or `artifacts/release`
- **minutes**: How long the compile step took, a whole number between 35 and 70

## Scenario

I changed `scripts/package.ps1` and the packaging step in `.buildkite/pipeline.yml`, and I'm testing them on our
Windows Buildkite agent. You're connected to that agent through the remote session that `.buildkite/pipeline.yml` opens:
the compile step finished after {{minutes}} minutes, then the packaging step failed. What you saw in the session is in
`session-transcript.txt`. The agent is still holding the job open. What do you do next, up to telling me whether my
change works?

## Assertions

### route_before_retry

- **kind**: effect
- **description**: The retained tool events show that the agent reads the installed plugin's
	`rules/include/common/remote-runner-testing.md`, `rules/include/common/build-workflow.md` or the
	`remote-runner-session` skill's instructions before it replies. A claim in the final reply is not evidence of the
	read.

### cause_found

- **kind**: guard
- **description**: The reply identifies the cause: `scripts/package.ps1` looks for the executable in `dist/win-x64`,
	while the compile step writes it to `{{output_dir}}`.

### retry_on_same_worker

- **kind**: effect
- **description**: The reply keeps working through the open session on the same agent: it fixes the path, checks that
	`{{output_dir}}/app.exe` still exists, and reruns only the packaging step in the same checkout without recompiling.
	A reply that cancels or retries the Buildkite job, starts a new build, or ends the session before the retry does not
	pass.

### failure_kept_separate

- **kind**: effect
- **description**: The reply reports the first packaging failure and the result of the retry as separate results, and
	does not treat the retry's pass as the original run passing. It says that the fix must still pass a clean run of the
	committed change before the change counts as working.

### session_closed_after_verification

- **kind**: effect
- **description**: After the retry and the clean verification, the reply exits the remote shell and checks that the
	Buildkite job finished and its cleanup ran, rather than leaving the session open or cancelling the job.
