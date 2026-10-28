# Incomplete integration repaired first

## Design Goals

- Prove that, before a remote test, the agent inspects the project's own session integration, finds that the changed
	commands can start before anyone connects and that cleanup runs before the worker is released or not at all after
	a failure, and repairs both instead of testing through that integration as it is.

## Variables

- **agent_label**: The label of the Jenkins agents that run the job, such as `linux-arm64` or `gpu-large`

## Scenario

I changed `scripts/integration.sh` and want to watch it run on one of our `{{agent_label}}` Jenkins agents through an
Upterm session, since it only fails there. The `Jenkinsfile` already has session support. Get everything ready so that
I can start the build with my public key, then tell me how we'll run the test.

## Assertions

### early_start_found

- **kind**: effect
- **description**: The reply says that `scripts/integration.sh` can start, and finish, before anyone connects, because
	the `Jenkinsfile` runs it right after `ci/start-session.sh` puts Upterm in the background, with no wait for a
	connected client.

### cleanup_order_found

- **kind**: effect
- **description**: The reply says that the `Cleanup` stage removes the session's files before the `Hold worker` stage
	waits for the session, and that after a failed `Integration` stage Jenkins skips the later stages, so the worker is
	not held for diagnosis.

### integration_repaired

- **kind**: effect
- **description**: The changed `Jenkinsfile` makes `scripts/integration.sh` start only after a signal that a connected
	client sends through the session, or leaves it for the connected client to run; holds the worker until the remote
	shell exits whether or not the integration stage fails, for example from a `post` `always` block; and removes the
	session's files only after that hold.

### no_build_started

- **kind**: guard
- **description**: The retained tool events show no Jenkins build started before the `Jenkinsfile` was changed.
