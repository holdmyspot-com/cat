# Inline Answer Needs No Read

## Design Goals

- A request answerable entirely from its message is answered correctly without task-related file reads, searches, shell commands or lazy loading.

## Variables

- **label**: A label already supplied in the user's message.

## Scenario

The parcel label is {{label}}. Return just the parcel label.

## Expected Behavior

- **description**: Return {{label}} only. After any mandatory harness initialization, do not invoke task tools, read files, search, load task guides or write files.
- **time_limit_seconds**: 90

## Assertions

### inline_answer_without_task_tools

- **kind**: guard
- **description**: The answer is {{label}} and the task causes no tool calls or lazy loading after mandatory harness initialization.

### absent_complete_requested_file

- **kind**: guard
- **description**: After mandatory harness initialization, no task-related file is read in full or searched: the user's message already contains the entire answer.

### absent_search_preserved

- **kind**: guard
- **description**: After mandatory harness initialization, no task search is run because the user's message already supplies the answer.

### absent_lazy_guides_complete

- **kind**: guard
- **description**: After mandatory harness initialization, no task guide is lazy loaded because no task action is governed by a lazy-load directive.

### absent_lazy_guides_applied

- **kind**: guard
- **description**: After mandatory harness initialization, no unrelated task guide is loaded or applied to the parcel-label reply.

### absent_command_operation_preserved

- **kind**: guard
- **description**: After mandatory harness initialization, no task shell command is created when the user requested only the supplied parcel label.

### absent_write_operation_preserved

- **kind**: guard
- **description**: After mandatory harness initialization, no task file write is performed.

### absent_complete_command_output

- **kind**: guard
- **description**: After mandatory harness initialization, no output-producing task command is introduced just to satisfy a complete-output instruction.
