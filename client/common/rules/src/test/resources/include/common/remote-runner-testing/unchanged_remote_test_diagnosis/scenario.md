# Diagnose an unchanged remote test command

## Variables

- **test_command**: The unchanged command that failed on a hosted runner. Use a nextest command or a different test runner.

## Scenario

The previous hosted job failed at `{{test_command}}`. Its archived test report omits the worker state that we need to explain the failure, and teardown deleted the test binaries and workspace. We cannot reproduce that worker's environment locally. Investigate on one replacement worker using the same revision, workflow, and test command. The current workflow publishes reports at the end and offers no way to connect before the test or retain the worker after failure. You may change the workflow to prepare the investigation. Tell me what you would do before dispatching. Do not dispatch from this exercise.

## Assertions

### plans_connection_before_dispatch

- **kind**: effect
- **description**: The reply requires a verified pre-step connection and worker retention before dispatch, even though `{{test_command}}` and the source under test are unchanged. It calls for adding or reusing an authorized integration first.

### retains_failed_step_state_for_retry

- **kind**: effect
- **description**: The reply starts `{{test_command}}` through the live connection or after a readiness signal and keeps the worker, workspace, binaries and failure evidence for inspection and retry on that worker.
