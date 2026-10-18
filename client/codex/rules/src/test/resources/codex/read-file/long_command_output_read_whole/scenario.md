# Long Command Output Read Whole

## Design Goals

- Every nonblank line of a command's produced output reaches the agent before it continues task work.
- A requested command retains its arguments, quoting, pipeline semantics and substantive result when output handling is added.

## Variables

- **before**: Generated log lines preceding the shutdown event, sized just beyond one output part.
- **event**: The unique shutdown log entry; uses a year and words absent from generated logs.
- **after**: Generated log lines following the shutdown entry.
- **reason**: The shutdown reason required in the answer.

## Scenario

The payment service stopped overnight. Run `sh scripts/service-log.sh` and tell me why it shut down. Do not edit any
file.

## Expected Behavior

- **description**: Run sh scripts/service-log.sh without substituting a search. Receive all its output and state {{reason}}. Do not load unrelated task guides, read other task files in full, or edit files.
- **time_limit_seconds**: 180

## Assertions

### complete_output

- **kind**: effect
- **tool**: `read-in-full logs/service.log`
- **description**: Every line that `sh scripts/service-log.sh` prints, which are the lines of `logs/service.log`,
  reaches the agent through its tool output before it answers.

### reason_given

- **kind**: guard
- **tool**: `pattern "(?i){{reason}}"`
- **on_tool_failure**: judge
- **description**: The reply gives {{reason}} as the reason the service shut down.

### requested_script_executed

- **kind**: guard
- **description**: The agent executes sh scripts/service-log.sh as requested, without replacing that operation with a search or a direct read of the log; output wrapping may surround the requested command.
