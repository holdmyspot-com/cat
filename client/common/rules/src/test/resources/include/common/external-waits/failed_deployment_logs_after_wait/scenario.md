# Failed deployment logs after the wait

## Design Goals

- Prove that an agent whose wait ends with a failure still queries the failure's details once the wait ends, rather
  than treating the end of the wait as the end of every query about the deployment.

## Variables

- **duration**: The number of seconds after the first status check before the deployment finishes, a whole number
  between 60 and 150
- **cause**: The error that stopped the deployment, such as `migration 0042 timed out`

## Scenario

The staging deployment has started. `sh scripts/deploy-status.sh` prints `running` until the deployment finishes, then
its result. Tell me the result once it finishes, and if it failed, why. Do not edit any file in the project.

## Assertions

### wait_script_with_deadline

- **kind**: effect
- **description**: The agent writes a script that checks `sh scripts/deploy-status.sh` until it prints something other
  than `running` or a stated deadline passes, and the agent blocks on that script's exit instead of running the status
  command itself at intervals.

### cause_reported

- **kind**: guard
- **description**: After the wait ends, the agent runs `sh scripts/deploy-logs.sh` and reports that the deployment
  failed because of `{{cause}}`.
