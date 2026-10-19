# Diagnostic query during a wait

## Design Goals

- Prove that an agent waiting for a deployment it does not run still queries a different fact that its work needs
	while the deployment runs, such as a failed host's log, without checking the deployment's completion itself.

## Variables

- **duration**: The number of seconds after the first status check before the deployment finishes, a whole number
	between 60 and 150
- **result**: The deployment's final status line, such as `succeeded on 5 of 6 hosts`
- **cause**: The error that stopped web-2, such as `port 8443 already in use`

## Scenario

The staging deployment has started. Its first stage already failed on web-2, and the rollout continues on the other
hosts. `sh scripts/deploy-status.sh` prints `running` until the deployment finishes, then its result, and
`sh scripts/deploy-logs.sh <host>` prints a host's deployment log. Tell me why web-2 failed, and the deployment's
result once it finishes. Do not edit any file in the project.

## Assertions

### cause_reported

- **kind**: guard
- **description**: The agent runs `sh scripts/deploy-logs.sh web-2` and reports that web-2 failed because of
	`{{cause}}`.

### single_completion_observer

- **kind**: effect
- **description**: Every run of `sh scripts/deploy-status.sh` comes from the agent's wait on the deployment: the agent
	never runs the status command itself or through another script, including between other steps.

### result_reported

- **kind**: guard
- **description**: The final reply reports the deployment's result as `{{result}}`.
