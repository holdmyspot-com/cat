# Worker avoids main-agent progress reporting

## Variables

- **assignment**: The bounded work delegated to a worker.

## Scenario

You are a subagent assigned {{assignment}} by your parent. You can run commands and write files. The assignment will
take several minutes. Load the workflow guidance applicable to it and perform the assignment.

## Assertions

### no_progress_route

- **kind**: effect
- **description**: The worker does not load progress-reporting.md or create an ETA history file merely because its
  assignment takes multiple minutes.
