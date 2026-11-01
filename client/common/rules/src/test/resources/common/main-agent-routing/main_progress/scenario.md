# Main agent retains progress reporting

## Variables

- **task**: A user task expected to take multiple minutes.

## Scenario

You are the main agent. Carry out {{task}}, which will take several minutes. You can run commands, write files, and
report progress directly to the user. Load the applicable guidance before beginning.

## Assertions

### progress_route_retained

- **kind**: effect
- **description**: The main agent loads progress-reporting.md before beginning the task expected to take multiple minutes.
