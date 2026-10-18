# Wait without short polls

## Design Goals

- Prove that an agent that waits for a deployment it does not run, with no other work to do, blocks on its wait with
  the longest limit that its wait operation and instructions allow and continues the same wait without checking the
  deployment, its wait's output or anything else in between, then reports the deployment's result.

## Variables

- **duration**: The number of seconds after the first status check before the deployment finishes, a whole number
  between 120 and 180
- **result**: The deployment's final status line, such as `succeeded` or `failed: health check timed out on web-2`

## Scenario

The staging deployment has started. `sh scripts/deploy-status.sh` prints `running` until the deployment finishes, then
its result. Tell me the result once it finishes.

## Assertions

### longest_wait_limit

- **kind**: effect
- **description**: Every call that waits on the agent's wait for the deployment requests the longest limit that the
  wait operation accepts, or the longest that the agent's instructions allow when they cap it, rather than a shorter
  limit such as a few seconds.

### no_completion_inspection

- **kind**: effect
- **description**: From the start of the agent's wait until it ends, no tool call runs `sh scripts/deploy-status.sh`
  outside the wait or reads the wait's output, its files or any log to learn whether the deployment finished.

### continuation_without_messages

- **kind**: effect
- **description**: When a call that waits on the deployment returns before the deployment finishes, the agent's next
  action is another call that waits on the same process, with no message to the user and no other tool call in
  between.

### result_reported

- **kind**: guard
- **description**: The final reply reports the deployment's result as `{{result}}`.
