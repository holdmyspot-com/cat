## Scenario

A reviewer examined the planned rule update. The earlier wording would require every input reader to preserve the exact
syntax the caller used, even though callers that only need the normalized value do not need that syntax. Callers whose
receiving operation must distinguish the exact syntax still need it preserved. Give the user a concise progress update
and state what you will do next.

## Assertions

### expected-versus-actual

Before identifying the review, the update explains that the earlier change would require every input reader to preserve exact syntax even though the caller only needs the normalized value.

### user-consequence

The update explains that preserving the unused exact syntax imposes unnecessary work or a requirement on a named affected actor, such as a caller, input reader, or receiving operation.

### concrete-next-action

The update names the callers that need only a normalized value and the callers whose receiving operation must distinguish exact syntax, states the corresponding result for each, and says it will verify both branches.
