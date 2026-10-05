## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

### Request 1

Rewrite this explanation so that its conclusion and exception are easy to understand:

> If the defect rate of each sampled production line is below 1%, continued inspection will eventually provide enough
> evidence for those lines to qualify. With enough inspections, every production line will qualify. A production line
> whose defect rate is 1% or higher may never qualify.

### Request 2

Make this release note precise without adding new requirements:

> If a tenant completed the data migration, its archived records are available through the new endpoint. Every tenant
> can retrieve its archived records through that endpoint. Tenants that have not completed the migration must continue
> using the existing endpoint.

## Assertions

### conditioned_probability_subset_retained

For request 1: The rewritten conclusion applies eventual qualification only to the production lines whose defect rate is
below 1%; it does not broaden that conclusion to every production line.

### conditioned_state_subset_retained

For request 2: The revised release note limits availability through the new endpoint to tenants that completed the
migration and does not claim that every tenant can use it.

### guidance_selection

The retained events establish that the executor selects `rules/include/common/write-clearly.md` before its first
governed action.
