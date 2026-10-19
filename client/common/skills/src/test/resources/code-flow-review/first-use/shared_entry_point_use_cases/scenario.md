# Shared entry point use cases

## Design Goals

- Prove that, when several use cases start at the same entry point, the first reply's overview still tells the user
	that they are separate behaviors, each started by its own actor action, and not consecutive steps of one flow.

## Variables

- **tool_name**: The name of the notes command-line tool, such as `jot` or `memo`

## Scenario

I changed `cli.py`, the `{{tool_name}}` command line. The change is saved as `change.diff` in this directory, and
`cli.py` already contains it. Use the code-flow-review skill to review the change.

## Assertions

### overview_says_use_cases_are_separate

- **kind**: effect
- **description**: Before the first use case's steps, the reply says that the listed use cases are separate behaviors
	that each start with their own actor action, and that they are not consecutive steps of one flow, even though they
	share the `main` entry point.

### one_row_per_command

- **kind**: guard
- **description**: The overview lists adding a note and removing a note as different use cases.
