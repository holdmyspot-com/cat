## Scenario

An active goal requires a production campaign receipt before a live provider campaign can run. The repository's named
campaign launcher is still unimplemented, but it is the local owner that creates the request, schedule, process tree,
and receipt. The production authority key and provider entitlement are not currently available. State whether the goal
may become blocked and the next action.

## Assertions

### local-owner-remains-actionable

The response keeps the goal active and implements or runs the launcher's next local acceptance gate; it does not ask
the user for the locally creatable request, schedule, process tree, or receipt.

### later-external-gate-does-not-block-local-work

The response identifies the authority key and provider entitlement as prerequisites only for the later live campaign
gate, not evidence that the unimplemented local launcher blocks the current goal.
