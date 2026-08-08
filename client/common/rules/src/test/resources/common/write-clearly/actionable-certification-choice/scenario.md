## Scenario

Rewrite this release update so the user can decide what to do next:

> The next deployment uses a slower migration path. Do you want to continue?

The current database remains compatible with both application versions. The migration will take 18–31 minutes and
temporarily reduce write throughput by an estimated 12–18%. The operator's maintenance window has 40 minutes
remaining. Continue now because the verified rollback takes under five minutes and the migration fits within the
remaining window. Deferring keeps the current database unchanged and requires scheduling another maintenance window.

## Assertions

### concrete-alternatives-and-consequences

The update states what continuing will run, how long it is expected to take, its operational effect, and what happens
if the user defers it.

### evidence-based-recommendation

The update recommends whether to continue and connects that recommendation to the supplied evidence and maintenance
window; it does not present an internal migration mechanism as the user's reason to choose.
