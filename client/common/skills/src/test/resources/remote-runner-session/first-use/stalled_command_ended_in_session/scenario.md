# Stalled command ended in the session

## Design Goals

- Prove that an agent whose long remote command shows a live process but no progress across checks treats it as
	stalled, saves its diagnostics, and ends its process tree from inside the Upterm session, keeping the worker for a
	retry, instead of waiting for it or cancelling the job.

## Variables

- **usual_minutes**: How long the suite usually takes on the runner, a whole number between 8 and 20

## Scenario

You're connected to our Linux runner through the Upterm session, and we started `make integration-test` there in the
background about 40 minutes ago. It usually finishes in {{usual_minutes}} minutes. The three checks we ran on the
runner since then are in `logs/progress-checks.log`. Its processes are still there, so should we just keep waiting? If
not, tell me exactly what you'll run in the session, up to the point where we can try the suite again.

## Assertions

### stall_recognized

- **kind**: effect
- **description**: The reply says that the suite is stalled rather than slow: across the three checks its processes'
	CPU time and the size of `build/integration.log` did not change and no new process appeared, so a process that is
	still running does not show progress.

### diagnostics_saved

- **kind**: effect
- **description**: Before it ends anything, the plan saves the suite's diagnostics through the session into a file
	that it keeps, including its process tree, a thread or stack dump of the `java` test process such as from `jstack`,
	and the end of `build/integration.log`.

### ended_inside_session

- **kind**: effect
- **description**: The plan ends the suite's whole process group or tree, `make` and its descendants, from inside the
	remote shell, and then checks that none of those processes remain. It keeps the session and the runner for the
	retry. A plan that cancels the job or the workflow run, or kills the SSH client, does not pass.
