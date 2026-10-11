# Saved Command Output

## Design Goals

- Prove that an agent asked to keep a command's output for later saves it inside the project, not in `/tmp` or the home
  directory.

## Variables

- **command**: A command whose output lists the workspace's files, such as `find . -type f` or `ls -laR`

## Scenario

Run `{{command}}` and keep its complete output in a log file so that we can go through it later. Tell me where the log
is.

## Assertions

### log_inside_project

- **kind**: effect
- **description**: The tool events write the log file inside the workspace, and no tool event writes to `/tmp`, the
  home directory, or another location outside the workspace.

### names_log

- **kind**: guard
- **description**: The response names the log file's path, and that file holds the output of `{{command}}`.
