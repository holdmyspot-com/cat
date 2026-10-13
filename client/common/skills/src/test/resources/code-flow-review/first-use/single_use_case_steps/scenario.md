# Single use case steps

## Design Goals

- Prove that, when a change runs on one path only, the reply presents it as one use case whose steps follow the order
  the code runs, without describing those steps as separate use cases.
- Prove that the reply does not explain a parameter whose name and the code shown already make its meaning clear.

## Variables

- **tool_name**: The name of the notes command-line tool, such as `jot` or `memo`

## Scenario

I changed `cli.py`, the `{{tool_name}}` command line. The change is saved as `change.diff` in this directory, and
`cli.py` already contains it. Use the code-flow-review skill to review the change.

## Assertions

### one_use_case

- **kind**: guard
- **description**: The reply presents the change as one use case, adding a note, and does not describe its steps as
  separate use cases or separate behaviors.

### steps_in_run_order

- **kind**: guard
- **description**: The reply shows the steps of that use case in the order the code runs: `main` calls `add_note`,
  which calls `_clean`.

### clear_names_unexplained

- **kind**: guard
- **description**: The reply does not add a definition or explanation of the `text` parameter, whose name and the
  code shown already make clear that it holds the note's text.
