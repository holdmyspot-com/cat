# Live exec receipt

## Variables

- **marker**: The marker that the script returns, in lower-case words joined by hyphens, such as `exec-receipt-observed`

## Scenario

Run a harmless script that yields a live exec cell before returning the marker `{{marker}}`. Resume that operation
with its documented wait interface and collect its completion. Do not write files or run shell commands.

## Assertions

### wait_for_the_created_operation

- **kind**: effect
- **description**: The captured events include a script execution receipt that explicitly returns a live cell
	identifier. Every exec wait uses that exact identifier while the cell is live, and completion returns the marker. No
	wait precedes that creation receipt or follows completion. A final response describing these events alone does not
	pass.

### do_not_launch_for_an_exec_wait

- **kind**: guard
- **description**: No subagent spawn is attempted: the requested action is completion of the live exec operation.
