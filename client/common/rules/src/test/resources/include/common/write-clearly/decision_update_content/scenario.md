# Decision update content

## Variables

- **window_minutes**: The minutes remaining in the operator's maintenance window, a whole number between 35 and 90, so
	that the migration fits

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Rewrite this release update so the user can decide what to do next:

> The next deployment uses a slower migration path. Do you want to continue?

The current database remains compatible with both application versions. The migration will take 18–31 minutes and
temporarily reduce write throughput by an estimated 12–18%. The operator's maintenance window has {{window_minutes}} minutes
remaining. Continue now because the verified rollback takes under five minutes and the migration fits within the
remaining window. Deferring keeps the current database unchanged and requires scheduling another maintenance window.

**Request 2.** Rewrite this prompt-optimization update so the user can choose between the two paths:

> You can continue optimizing this prompt or revise the surrounding prompt system.

Seven of the last nine failures occurred only when `review.md` and `write.md` were loaded together. Continuing to
optimize only `write.md` is estimated to cost $28–$46. Repairing the conflicting precedence rules in both files,
revalidating their interaction, and starting certification for the changed environment is estimated to cost $14–$24.
The existing certificate will become stale if either file changes. CAT recommends repairing the conflict because its
estimated total cost is lower and the observed failures are tied to the interaction rather than to `write.md` alone.

**Request 3.** Improve this explanation for a statistician who asked how CAT controls false certification across repeated candidate
batches:

> CAT assigns each batch part of one campaign-wide error allowance. The assigned portions never sum to more than 5%.

The reader is asking for an explanation, not choosing whether to run a batch.

## Assertions

### concrete_alternatives_and_consequences

- **kind**: effect
- **description**: For request 1: The update states what continuing will run, how long it is expected to take, its
	operational effect, and what happens if the user defers it.

### evidence_based_recommendation

- **kind**: effect
- **description**: For request 1: The update recommends whether to continue and connects that recommendation to the
	supplied evidence and maintenance window; it does not present an internal migration mechanism as the user's reason to
	choose.

### concrete_scope_and_consequences

- **kind**: effect
- **description**: For request 2: The update identifies the files and work in each path, explains the certificate
	effect, and compares the supplied cost ranges.

### current_recommendation_and_reason

- **kind**: effect
- **description**: For request 2: The update recommends one path from the supplied failure and cost evidence rather than
	offering an unexplained option to revise a broad surrounding system.

### mechanism_remains_direct

- **kind**: guard
- **description**: For request 3: The explanation may directly describe the error-allocation mechanism. It does not
	invent costs, alternatives, or a recommendation that the reader did not request.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`
	before its first governed action.
