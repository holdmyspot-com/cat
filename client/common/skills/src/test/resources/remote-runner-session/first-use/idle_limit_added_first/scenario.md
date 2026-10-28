# Idle limit added first

## Design Goals

- Prove that, before a remote test, the agent inspects the project's own session integration, finds that it starts
	Upterm with no idle limit, so that a session that nobody joins and a joined session left idle both hold the worker
	until the job's timeout, and repairs it instead of testing through that integration as it is.

## Variables

- **runner**: The label of the GitHub-hosted Linux runner that the job runs on, such as `ubuntu-24.04` or
	`ubuntu-24.04-arm`

## Scenario

I changed `scripts/integration.sh` and it only fails on the `{{runner}}` runners, so I'd like to run it there through
an Upterm session. `.github/workflows/integration.yml` already has session support. Get everything ready so that I can
dispatch the workflow with my public key, then tell me how we'll run the test.

## Assertions

### idle_limit_missing_found

- **kind**: effect
- **description**: The reply says that `ci/remote-session/start.sh` starts Upterm without a join timeout and hosts a
	plain `bash` that never ends by itself, so a session that nobody joins, or one whose guest leaves it idle or
	disconnects without exiting the shell, keeps the runner until the job's 180-minute timeout.

### idle_limit_added

- **kind**: effect
- **description**: The changed integration ends a session that nobody joins within 30 minutes, for example with
	`--join-timeout 30m` on `upterm host`, and makes the hosted command end itself after 30 minutes without input from
	a guest without ending a command that is still running, for example by hosting a bash shell whose `TMOUT` is 1800
	and read-only. The workflow still runs its cleanup after the session ends.

### no_run_dispatched

- **kind**: guard
- **description**: The retained tool events show no workflow run dispatched before the session integration was
	changed.
