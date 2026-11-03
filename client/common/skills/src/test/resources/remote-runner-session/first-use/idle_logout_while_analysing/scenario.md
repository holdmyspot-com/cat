# Idle logout while analysing

## Design Goals

- Prove that an agent whose earlier Upterm session ended at an idle prompt while it analysed a failure finds that cause,
	and plans to keep a foreground keepalive in the remote shell whenever no other command runs, to track the worker's
	limits as clock times, and to release the worker itself once nothing needs it, rather than only sending a short
	command now and then.

## Variables

- **timeout_minutes**: The job's `timeout-minutes`, a whole number between 60 and 240

## Scenario

The last time we debugged `scripts/integration.sh` on the runner, the Upterm session died while you were reading the
failure, and we lost the runner and its build. The session's transcript is in `logs/session-transcript.log`. I'm about
to dispatch `.github/workflows/integration.yml` again with my public key. What went wrong, and what will you do
differently this time, step by step until we're done with the runner?

## Assertions

### logout_cause_found

- **kind**: effect
- **description**: The reply says that the hosted shell logged itself out after 10 minutes at its prompt, because
	`ci/remote-session/start.sh` hosts a bash shell whose read-only `TMOUT` is 600, as the transcript's
	`timed out waiting for input: auto-logout` shows, although the client was still connected. A reply that blames a
	dropped connection or the job's timeout does not pass.

### keepalive_planned

- **kind**: effect
- **description**: The plan starts a command that keeps running in the remote shell's foreground right after it
	verifies the connection and before it analyses anything, keeps one running whenever no other command runs,
	including while it reads a failure, and ends it before each next command with a key or marker that the keepalive
	handles, not with Ctrl-C. A plan that only sends a short command now and then before the limit does not pass.

### deadlines_tracked

- **kind**: effect
- **description**: The plan records, as clock times, the 10-minute deadline for joining the session, the 10-minute
	idle limit at the prompt and the job's {{timeout_minutes}}-minute timeout, and connects as soon as the SSH command
	appears in the job's log.

### released_when_done

- **kind**: effect
- **description**: Once nothing needs the runner, the plan copies the outputs it still needs, stops the keepalive,
	runs `exit` in the remote shell and checks the job's result and its `Clean up` step, rather than leaving the
	session to reach its idle limit or the job's timeout.

### no_run_dispatched

- **kind**: guard
- **description**: The retained tool events show no workflow run dispatched and no SSH or Upterm connection.
