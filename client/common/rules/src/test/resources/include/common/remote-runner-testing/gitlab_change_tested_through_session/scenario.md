# GitLab change tested through a session

## Design Goals

- Prove that an agent asked to test a changed job on a self-hosted GitLab runner plans to open and verify an
	authenticated session to that runner before the changed command runs, keeps it open through the result, and then
	ends it and observes the job's cleanup, rather than letting the job run the command unattended.

## Variables

- **runner_tag**: The tag of the self-hosted GitLab runner that the job selects, such as `linux-gpu` or `arm-builder`
- **command**: The job's changed test command, such as `make integration-test` or `ci/run-suite.sh --shard 2`

## Scenario

I changed the `integration` job in `.gitlab-ci.yml` so that it runs `{{command}}`. It only runs on our self-hosted
`{{runner_tag}}` runner, so test it there before I merge. I start pipelines from my machine, so tell me, in order, every
step you'll take, including what you need me to run, until you can tell me whether it passes.

## Assertions

### route_before_plan

- **kind**: effect
- **description**: The retained tool events show that the agent reads the installed plugin's
	`rules/include/common/remote-runner-testing.md` or the `remote-runner-session` skill's instructions before it
	replies with its plan. A claim in the final reply is not evidence of the read.

### session_verified_before_command

- **kind**: effect
- **description**: The plan has the agent create or use its own SSH key pair and give only the public key to the
	pipeline as `REMOTE_SESSION_PUBLIC_KEY`, read the SSH command from the live job log while the job runs, connect
	with the private key and the pinned `ci/upterm_known_hosts`, and check through that connection that a command takes
	effect in the job's workspace, all before `{{command}}` starts. Only then does it create
	`.remote-session/client-ready`, or run `{{command}}` through the connected shell itself. A plan that lets the job
	run `{{command}}` before the agent is connected, or that connects only after a failure, does not pass.

### session_held_then_closed

- **kind**: effect
- **description**: The plan keeps the session open while `{{command}}` runs and diagnoses a failure through it in the
	same job's workspace, instead of starting a new pipeline. Once the command and any retries pass, it exits the remote
	shell and checks the job's final status and the `after_script` cleanup, and only then removes its key pair.

### changed_command_tested

- **kind**: guard
- **description**: The plan tests the `integration` job's `{{command}}` as written on the `{{runner_tag}}` runner and
	does not propose editing `.gitlab-ci.yml` or replacing the command.
