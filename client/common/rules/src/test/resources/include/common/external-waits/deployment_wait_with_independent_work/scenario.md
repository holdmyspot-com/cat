# Deployment wait with independent work

## Design Goals

- Prove that an agent that must wait for a deployment it does not run, and that also has work which does not depend on
  the deployment, starts one wait that alone checks whether the deployment finished, does that work while the
  deployment runs instead of blocking first or checking the deployment between steps, then reports its result.

## Variables

- **duration**: The number of seconds after the first status check before the deployment finishes, a whole number
  between 60 and 150
- **result**: The deployment's final status line, such as `succeeded` or `failed: health check timed out on web-2`
- **entry**: A changelog entry, such as `Show the invoice number on payment receipts.`

## Scenario

The staging deployment has started. `sh scripts/deploy-status.sh` prints `running` until the deployment finishes, then
its result. Tell me the result once it finishes. Also add `- {{entry}}` to the Unreleased section of `CHANGELOG.md`.

## Assertions

### independent_work_before_blocking

- **kind**: effect
- **description**: The agent edits `CHANGELOG.md` before it blocks on the wait for the deployment, rather than first
  waiting for the deployment to finish and editing the file afterwards.

### single_completion_observer

- **kind**: effect
- **description**: The agent starts its wait on the deployment before it edits `CHANGELOG.md`, and every run of
  `sh scripts/deploy-status.sh` comes from that wait: the agent never runs the status command itself or through another
  script, including between other steps.

### wait_script_with_deadline

- **kind**: effect
- **description**: The agent writes a script that checks `sh scripts/deploy-status.sh` until it prints something other
  than `running` or a stated deadline passes, and the agent blocks on that script's exit instead of running the status
  command itself at intervals.

### longest_wait_limit

- **kind**: effect
- **description**: Once the agent has edited `CHANGELOG.md`, every call that waits on its wait for the deployment
  requests the longest limit that the wait operation accepts, or the longest that the agent's instructions allow when
  they cap it, rather than a shorter limit such as a few seconds.

### changelog_entry_added

- **kind**: guard
- **description**: `CHANGELOG.md` lists `- {{entry}}` under `## Unreleased` and is otherwise unchanged.

### result_reported

- **kind**: guard
- **description**: The final reply reports the deployment's result as `{{result}}`.
