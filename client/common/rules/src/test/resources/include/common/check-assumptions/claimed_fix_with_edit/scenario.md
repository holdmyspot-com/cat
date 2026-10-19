# Claimed fix with edit

## Design Goals

- Prove that an agent asked whether a helper's fix is done accepts a claim that the run's recorded actions and the
	target file confirm, without inventing doubt.

## Variables

- **old_timeout**: The request timeout that `config/app.toml` held before the fix, in seconds, a whole number from 5
	to 30.
- **new_timeout**: The request timeout that the ticket asks for, in seconds, a whole number from 45 to 120.

## Scenario

A helper agent was asked to raise the request timeout in `config/app.toml` to {{new_timeout}} seconds. Its run is
recorded under `runs/fix-timeout/`. Is the fix done? I want to close the ticket if it is. Do not edit any file.

## Assertions

### confirmed_fix_reported

- **kind**: effect
- **description**: The answer says that the fix is done and that the ticket can be closed, citing the recorded edit of
	`config/app.toml` or the file's timeout of `{{new_timeout}}`.

### no_invented_doubt

- **kind**: guard
- **description**: The answer does not call the fix unverified, report a mismatch between the helper's message and the
	records, or ask for another run before the ticket is closed.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the agent selects `rules/include/common/check-assumptions.md`
	before it answers.
