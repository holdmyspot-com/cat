# Remote Runner Testing

## Design Goals

- When an agent tests changed workflow commands or investigates an unresolved failure that needs the remote worker
	or its workspace, its evidence shows that the relevant commands ran on that worker while an authenticated
	interactive session was live. The workflow is ready to retain that worker before the agent dispatches it.
- A failure on the remote runner is diagnosed and retried on the same retained worker and workspace, and the original
	failure stays distinct from the retry's result.
- The agent ends the session deliberately as soon as no remaining command, retry or required verification needs the
	worker, and observes the job's terminal result and the worker's cleanup instead of assuming them, or reports the
	cleanup as unobserved when it deliberately cancelled steps whose results nobody needed.
- Reading workflow files, local tests, and routine acceptance runs with unchanged workflow commands need no remote
	session.

## Guidance

Use this rule before dispatching a hosted run for either purpose:

- Test a change to the workflow definition or to a script, action, or build setting it runs on the worker, even when
	you expect the run to pass.
- Investigate an unresolved failure of the project's commands when you may need to inspect state that teardown
	removes or retry in the same workspace. Apply this trigger even when you rerun the original workflow, command,
	and revision unchanged.

Apply the rule across runner providers and operating systems. Read-only review, local tests, and routine hosted
acceptance with unchanged commands need no session when the required result is the workflow's terminal record.

When a project command failed on another machine, lazy load `ci-debugging.md` before choosing the reproduction
boundary. Use its local-first procedure and its branch for hosted-only boundaries that remote sessions cannot exercise.

Use the `cat:remote-runner-session` skill, whose instructions are in
`${CAT_PLUGIN_ROOT}/skills/remote-runner-session/SKILL.md`, for the procedure. Before dispatch, verify from the
workflow and its integration that you can connect while the worker runs, that the relevant step waits for the
connection or runs through it, and that teardown waits for the session to end even if the step fails. If that path is
missing, reuse or repair the integration within the task's authority before dispatching the diagnostic run. Keep any
archived evidence and report the missing capability if you cannot arrange the session.

Connect to each worker under test and verify that a command you send through the session takes effect in the job's
workspace. Then run the relevant commands through that session or release them with a readiness signal sent through
it. A failure-only connection opens after those commands have already run and cannot provide this evidence.

When a command under test fails on the worker, lazy load `build-workflow.md` before you choose where to resume, and apply
it on the same worker and in the same workspace: keep its valid outputs and the original failure's evidence. The
skill's "Diagnose and retry" and "Shut down and verify" sections direct keeping the session open, reconnecting, ending
the session, observing cleanup, and reporting an expired or idle-ended session.

Record the live connection, the commands you ran, their results, and the state that the worker retained. Report the
original failure and a later successful retry as separate results. Starting a session, publishing its connection
details, or printing a login banner does not show that a command ran.
