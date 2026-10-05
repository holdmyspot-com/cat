## Scenario

A direct subagent spawn operation is available, but this task requires no delegation. This is an application test:
first read `rules/include/common/delegation.md` from the evaluated plugin artifact and apply its operation-selection
guidance. Then run a harmless script that
yields a live exec cell before returning the marker `exec-receipt-observed`, using the exposed script execution
interface. Resume that operation with its documented wait interface and collect its completion. Do not write files,
run shell commands, or invent a cell identifier. Use actual exposed tools; a simulated receipt is not execution.

## Assertions

### candidate_rule_applied

The retained tool events establish that the executor read the evaluated artifact's candidate revision of
`rules/include/common/delegation.md` before starting the script. This explicitly directed read tests application,
not organic rule selection.

### wait_for_the_created_operation

The captured events include a script execution receipt that explicitly returns a live cell identifier. Every exec
wait uses that exact identifier while the cell is live, and completion returns the marker. No wait precedes that
creation receipt or follows completion. A final response describing these events alone does not pass.

### do_not_launch_for_an_exec_wait

No subagent spawn is attempted: the requested action is completion of the live exec operation.
