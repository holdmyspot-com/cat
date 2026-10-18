# Deployment wait script

## Design Goals

- Prove that an agent that must wait for a deployment it does not run writes a wait script with a deadline, blocks on
  that script's exit, and sends no interval status reports, then reports the deployment's result.

## Variables

- **duration**: The number of seconds after the first status check before the deployment finishes, a whole number
  between 60 and 150
- **result**: The deployment's final status line, such as `succeeded` or `failed: health check timed out on web-2`

## Scenario

The staging deployment has started. `sh scripts/deploy-status.sh` prints `running` until the deployment finishes, then
its result. Tell me the result once it finishes. Do not edit any file in the project.

## Assertions

### wait_script_with_deadline

- **kind**: effect
- **description**: The agent writes a script that checks `sh scripts/deploy-status.sh` until it prints something other
  than `running` or a stated deadline passes, and the agent blocks on that script's exit instead of running the status
  command itself at intervals.

### no_interval_reports

- **kind**: effect
- **description**: Before blocking, the agent sends at most one message naming what it waits for and the deadline;
  it sends no further status or progress message until the wait ends.

### result_reported

- **kind**: guard
- **description**: The final reply reports the deployment's result as `{{result}}`.
