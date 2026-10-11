# Locally creatable prerequisite

## Variables

- **external_prerequisites**: The external prerequisites of the live provider campaign that are not currently available,
  such as `The production authority key and provider entitlement`

## Scenario

An active goal requires a production campaign receipt before a live provider campaign can run. The repository's named
campaign launcher is still unimplemented, but it is the local owner that creates the request, schedule, process tree,
and receipt. {{external_prerequisites}} are not currently available. State whether the goal
may become blocked and the next action.

## Assertions

### local_owner_remains_actionable

- **kind**: effect
- **description**: The response keeps the goal active and implements or runs the launcher's next local acceptance gate;
  it does not ask the user for the locally creatable request, schedule, process tree, or receipt.

### later_external_gate_does_not_block_local_work

- **kind**: effect
- **description**: The response identifies {{external_prerequisites}} as prerequisites only for the later live campaign
  gate, not evidence that the unimplemented local launcher blocks the current goal.
