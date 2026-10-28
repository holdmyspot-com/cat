# Remote Runner Testing

## Design Goals

- When an agent tests changed workflow commands on a remote runner, its evidence shows that those commands ran on that
	worker while an authenticated interactive session to the worker was live; a configured, started, or failure-only
	session is not that evidence.
- A failure on the remote runner is diagnosed and retried on the same retained worker and workspace, and the original
	failure stays distinct from the retry's result.
- The agent ends the session deliberately once the required verification is done, and observes the worker's cleanup
	instead of assuming it.
- Reading workflow files and running the changed commands on the agent's own machine start no remote session.

## Guidance

This rule applies when you run a CI or other hosted workflow on a remote runner to test a change to the commands it runs
there: the workflow definition, or a script, action, or build setting that the workflow runs on the worker. It applies
on every runner provider and operating system, and also when you expect the run to pass. Reading or reviewing workflow
files, and running the changed commands on your own machine, need no session.

When the change repairs a failed CI or hosted-workflow step, lazy load `ci-debugging.md` before you dispatch the remote
run: a local run that exercises the failing boundary comes first and needs no session.

Use the `cat:remote-runner-session` skill, whose instructions are in
`${CAT_PLUGIN_ROOT}/skills/remote-runner-session/SKILL.md`, for the procedure. Before the changed commands start, make
sure the worker will outlive them, open an authenticated interactive session to each worker under test, and verify that
a command you send through it takes effect in the job's workspace. Then run the changed commands through that session,
or let the workflow start them only after a readiness signal that you send through it, so they cannot finish before you
are connected. A session that the workflow opens only after a step fails does not meet this rule, because the changed
commands have already run unobserved.

If the project's workflow integration cannot keep the worker or give you that access, report the missing piece and
repair the integration before the remote test. Do not fall back to running the changed commands unattended.

Keep the session open while the changed commands run and while you diagnose and retry a failure. When a changed command
fails on the worker, lazy load `build-workflow.md` before you choose where to resume, and apply it on the same worker
and in the same workspace: keep its valid outputs and the original failure's evidence. Do not end the session to start
a replacement worker. If only your client's connection drops while the worker is still available, reconnect to the same
worker and verify its retained state before you continue.

Record the live connection, the commands you ran, their results, and the state that the worker retained. Report the
original failure and a later successful retry as separate results. Starting a session, publishing its connection
details, or printing a login banner does not show that a command ran.

After the changed commands and any retries have passed and the task's required clean verification has finished, exit
the remote shell deliberately, wait for your client process to end, and observe the provider's terminal job and cleanup
result. If the session expires before anyone connects, report that outcome with the original logs instead of a passing
test.
