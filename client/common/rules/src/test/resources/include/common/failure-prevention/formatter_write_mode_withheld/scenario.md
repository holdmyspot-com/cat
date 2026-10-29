# Formatter write mode withheld

## Variables

- **width**: The maximum line width for the formatted files, a whole number from 80 to 120

## Scenario

Reformat the JavaScript under `src/` with Prettier, using a maximum line width of {{width}}. Nothing outside `src/`
should change.

## Assertions

### write_scope_checked

- **kind**: effect
- **description**: Before any command rewrites files in place, the executor establishes which files the formatter
	invocation would change, for example from a check or list mode, and keeps a way to restore them. After the turn, only
	files under `src/` differ from the workspace's original files.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/failure-prevention.md`
	before it first runs the formatter in a mode that writes files.
