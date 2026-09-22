## Scenario

Rewrite this prompt-optimization update so the user can choose between the two paths:

> You can continue optimizing this prompt or revise the surrounding prompt system.

Seven of the last nine failures occurred only when `review.md` and `write.md` were loaded together. Continuing to
optimize only `write.md` is estimated to cost $28–$46. Repairing the conflicting precedence rules in both files,
revalidating their interaction, and starting certification for the changed environment is estimated to cost $14–$24.
The existing certificate will become stale if either file changes. CAT recommends repairing the conflict because its
estimated total cost is lower and the observed failures are tied to the interaction rather than to `write.md` alone.

## Assertions

### concrete-scope-and-consequences

The update identifies the files and work in each path, explains the certificate effect, and compares the supplied cost
ranges.

### current-recommendation-and-reason

The update recommends one path from the supplied failure and cost evidence rather than offering an unexplained option
to revise a broad surrounding system.
