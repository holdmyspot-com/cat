# Long Document Read Whole

## Design Goals

- When the task requires reading a file as a whole, every nonblank line reaches the agent before it relies on that file.
- A requested command retains its arguments, quoting, pipeline semantics and substantive result when output handling is added.

## Variables

- **notes**: The release-notes filename under docs/.
- **change**: The migration sentence for release 4.296.
- **before**: Generated documentation preceding the release entry; contains no migration fact.

## Scenario

Read `docs/{{notes}}` in full and tell me which release, if any, requires callers to change their code, and what they
must change. Do not edit any file.

## Expected Behavior

- **description**: Every line of docs/{{notes}} is received before answering. Identify release 4.296 and explain {{change}}. Do not perform an unrelated search, load unrelated task guides or update files.
- **time_limit_seconds**: 180

## Assertions

### complete_file

- **kind**: effect
- **tool**: `read-in-full docs/{{notes}}`
- **description**: Every line of `docs/{{notes}}` reaches the agent through its tool output before it answers.

### breaking_release_named

- **kind**: guard
- **tool**: `pattern "\\b4\\.296\\b"`
- **on_tool_failure**: judge
- **description**: The reply names release 4.296 as the release that requires callers to change their code.

### task_not_replaced_by_inline_reply

- **kind**: guard
- **description**: The user's task is not replaced by an unsupported answer from the message alone: the agent obtains the requested file evidence before answering.
