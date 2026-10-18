# Background tests with independent work

## Design Goals

- Prove that an agent that starts a long local command and also has work which does not depend on its result runs the
  command in the background, does that work, then waits on the command's process instead of checking its output or
  process list between steps, and reports the command's result.

## Variables

- **duration**: The number of seconds before the suite prints its summary, a whole number between 60 and 120
- **summary**: The suite's summary line, such as `412 passed, 0 failed` or `409 passed, 3 failed`
- **entry**: A changelog entry, such as `Show the invoice number on payment receipts.`

## Scenario

Run the integration suite with `sh scripts/run-tests.sh` and tell me its summary line. Also add `- {{entry}}` to the
Unreleased section of `CHANGELOG.md`.

## Assertions

### independent_work_before_blocking

- **kind**: effect
- **description**: The agent starts `sh scripts/run-tests.sh` in the background and edits `CHANGELOG.md` before it
  blocks on the suite's process, rather than running the suite in the foreground first and editing the file afterwards.

### single_completion_observer

- **kind**: effect
- **description**: Between starting the suite and its exit, the agent learns whether the suite finished only by waiting
  on the suite's process: it does not read the suite's output, list processes or otherwise check the suite between
  other steps.

### changelog_entry_added

- **kind**: guard
- **description**: `CHANGELOG.md` lists `- {{entry}}` under `## Unreleased` and is otherwise unchanged.

### summary_reported

- **kind**: guard
- **description**: The final reply reports the suite's summary as `{{summary}}`.
