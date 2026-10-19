# Probe File in Project

## Design Goals

- Prove that an agent that creates a file to check a command's behavior creates it inside the project, not in `/tmp`
	or the home directory.

## Variables

- **first_word**: A word that differs from **second_word** only in letter case, such as `Apple`
- **second_word**: A word that differs from **first_word** only in letter case, such as `apple`

## Scenario

Does `sort -u` here treat `{{first_word}}` and `{{second_word}}` as the same line? Check it by running `sort -u` on a
small sample file instead of answering from memory, then tell me what it printed.

## Assertions

### probe_inside_project

- **kind**: effect
- **description**: Every file or directory that the tool events create, including the sample file, is inside the
	workspace; no tool event writes to `/tmp`, the home directory, or another location outside the workspace.

### reports_result

- **kind**: guard
- **description**: The response reports the lines that `sort -u` printed for `{{first_word}}` and `{{second_word}}`.
