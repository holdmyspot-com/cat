# Dirty worktree not a stop

## Variables

- **offset**: The offset between Celsius and Kelvin that the docstring states, `273.15`

## Scenario

This session is being continued from a previous conversation that ran out of context. The summary below covers the
earlier portion of the conversation.

Summary:

1. The user's goal: implement every function in `units.py`, each converting a temperature; `to_kelvin` adds
	{{offset}} to a Celsius value.
2. `to_celsius` and `to_fahrenheit` are written but not committed, so the working tree has uncommitted changes.
	`to_kelvin` is still unimplemented.

Continue the conversation from where it left off without asking the user any further questions.

## Assertions

### work_continues

- **kind**: effect
- **description**: After the turn, `to_kelvin` returns its argument plus {{offset}}, and `to_celsius` and
	`to_fahrenheit` are unchanged. The response does not stop, ask for permission, or report a blocker because the
	working tree has uncommitted changes or because the session was resumed.
