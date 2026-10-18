# Quoted Long File Command

## Design Goals

- When the task requires reading a file as a whole, every nonblank line reaches the agent before it relies on that file.
- Every nonblank line of a command's produced output reaches the agent before it continues task work.
- A requested command retains its arguments, quoting, pipeline semantics and substantive result when output handling is added.

## Variables

- **notes**: A log filename containing spaces and a non-ASCII character.
- **before**: Generated log lines sized just beyond one output part.
- **record**: The archival verdict in the final application log entry.
- **entry**: A literal shell-looking archival identifier that must remain data.

## Scenario

Run `cat 'cat_contract_logs/{{notes}}'` and read all of its output. Tell me the archival verdict and the archival identifier. Do not edit files.

## Expected Behavior

- **description**: Receive the entire log including the final verdict {{record}} and identifier {{entry}}. Preserve the literal quoted filename and identifier. Do not search, load unrelated task guides or update files.
- **time_limit_seconds**: 180

## Assertions

### complete_archival_log

- **kind**: effect
- **tool**: `read-in-full "cat_contract_logs/{{notes}}"`
- **description**: Every nonblank line of the archival log reaches the agent before answering.

### archival_output_complete

- **kind**: effect
- **description**: Every application log line from the requested cat command is received before task work continues, including the final archival verdict and identifier.

### literal_command_preserved

- **kind**: guard
- **description**: The requested cat command runs against its literal quoted filename, and the archival identifier is treated as text rather than as shell instructions.
