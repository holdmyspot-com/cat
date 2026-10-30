# New function documented

## Design Goals

- Prove that defining a Bash function, in a task that does not ask for its documentation, selects the Bash
	documentation rule before the function is written, so the new function is documented.

## Variables

- **keep**: The number of newest backups to keep, such as `5` or `7`

## Scenario

Change `backup.sh` so that it keeps only the newest {{keep}} backups. Put the clean-up in a function and call it after
the copy.

## Assertions

### old_backups_pruned

- **kind**: effect
- **description**: `backup.sh` defines a function that removes every backup folder under the backup root except the
	{{keep}} newest, and calls it after the copy. The copy itself is unchanged.

### function_documented

- **kind**: effect
- **description**: The new function has a comment block immediately above its definition that states its
	responsibility in one sentence and describes each input it reads, such as the backup root and the number of backups
	to keep, by its meaning rather than only as `$1` or a variable name, and the folders it deletes.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/bash/documentation.md` before
	it edits `backup.sh`.
