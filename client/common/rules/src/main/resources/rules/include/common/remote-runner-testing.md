# Remote Runner Testing

## Design Goals

- When an agent tests changed workflow commands on a remote runner, its evidence shows that those commands ran on that
	worker while an authenticated interactive session to the worker was live; a configured, started, or failure-only
	session is not that evidence.
- A failure on the remote runner is diagnosed and retried on the same retained worker and workspace, and the original
	failure stays distinct from the retry's result.
- The agent ends the session deliberately as soon as no remaining command, retry or required verification needs the
	worker, and observes the job's terminal result and the worker's cleanup instead of assuming them, or reports the
	cleanup as unobserved when it deliberately cancelled steps whose results nobody needed.
- Reading workflow files and running the changed commands on the agent's own machine start no remote session.

## Guidance

This rule applies when you run a CI or other hosted workflow on a remote runner to test a change to the commands it runs
there: the workflow definition, or a script, action, or build setting that the workflow runs on the worker. It applies
on every runner provider and operating system, and also when you expect the run to pass. Reading or reviewing workflow
files, and running the changed commands on your own machine, need no session.

When the change repairs a failure of the project's commands on another machine, lazy load `ci-debugging.md` before you
dispatch the remote run: a local run that exercises the failing boundary comes first and needs no session.

Use the `cat:remote-runner-session` skill, whose instructions are in
`${CAT_PLUGIN_ROOT}/skills/remote-runner-session/SKILL.md`, for the procedure. Before the changed commands start, make
sure the worker will outlive them, open an authenticated interactive session to each worker under test, and verify that
a command you send through it takes effect in the job's workspace. Then run the changed commands through that session,
or let the workflow start them only after a readiness signal that you send through it, so they cannot finish before you
are connected. A session that the workflow opens only after a step fails does not meet this rule, because the changed
commands have already run unobserved.

When a changed command fails on the worker, lazy load `build-workflow.md` before you choose where to resume, and apply
it on the same worker and in the same workspace: keep its valid outputs and the original failure's evidence. The
skill's "Diagnose and retry" and "Shut down and verify" sections direct keeping the session open, reconnecting, ending
the session, observing cleanup, and reporting an expired or idle-ended session.

Record the live connection, the commands you ran, their results, and the state that the worker retained. Report the
original failure and a later successful retry as separate results. Starting a session, publishing its connection
details, or printing a login banner does not show that a command ran.
