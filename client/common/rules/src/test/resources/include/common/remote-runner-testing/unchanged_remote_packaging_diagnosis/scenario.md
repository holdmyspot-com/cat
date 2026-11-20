# Diagnose an unchanged remote packaging command

## Variables

- **packaging_command**: The unchanged packaging command whose output can only be inspected on the hosted worker. Vary the command and runner provider.

## Scenario

Our last hosted packaging job failed at `{{packaging_command}}`. The report names the failed step. The intermediate bundle that distinguishes the two possible causes only exists in the job's workspace, which was deleted. The local machine cannot run the worker platform. The workflow can provide authenticated access before a step and hold the worker through retries. Diagnose this on one worker without changing the packaging command. Explain how you will start and observe the rerun. Do not dispatch from this exercise.

## Assertions

### uses_existing_live_runner

- **kind**: effect
- **description**: The reply uses the existing remote access before starting `{{packaging_command}}`, confirms access to the job workspace, and retains that same worker through inspection and any retry.
