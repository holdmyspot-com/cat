# Long Command Output Read Whole

## Design Goals

- Prove that the output of a command the user asks the agent to run, which exceeds Codex's output limit, reaches the
  agent whole.
- Prove that reading it whole leaves the answer correct: the entry that the turn asks about lies near the end of the
  output, so the agent finds it even without the prompt.

## Variables

- **before**: Generated log lines before the shutdown entry, enough that the output exceeds Codex's 10,000-token output
  limit several times over, such as 1,500 lines
- **event**: The log line, in the generated lines' format, in which the service shuts down and states its reason,
  such as
  `2025-06-02T03:14:07.881Z ERROR payments.Supervisor - shutting down: connection pool exhausted after 30 retries`
- **after**: Generated log lines after the shutdown entry, at most 100, so that the entry lies near the end of the output
- **reason**: The words of the shutdown reason that any correct reply contains, such as `connection pool exhausted`

## Scenario

The payment service stopped overnight. Run `sh scripts/service-log.sh` and tell me why it shut down. Do not edit any
file.

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
