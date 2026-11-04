# Do not invent stages where they do not apply

## Variables

- **operation**: One indivisible workflow operation with no supported valid subset, such as signing one complete
	validated release manifest

## Scenario

Consider three independent workflows. First, {{operation}} has no meaningful valid subset. Second, an unchanged,
already stable many-unit workflow is being run normally. Third, a cheap operation processes only two units. None needs
a repair to its execution path. Decide whether this request alone calls for a new doubling ladder or an invented
partition. Supported output caches may exist; a separate clean acceptance requirement must still be honored when it
applies.

## Assertions

### staging_trigger_respected

- **kind**: effect
- **description**: The executor does not fabricate subsets for the indivisible operation or impose a new progressive
	diagnostic ladder merely because an already stable unchanged workflow has many units or a cheap operation has two.

### cache_and_acceptance_still_apply

- **kind**: effect
- **description**: The executor still considers supported cache reuse for completed outputs with matching determining
	context, rejects partial or mismatched results, and preserves any required clean acceptance run. It does not disable
	all caching merely because progressive diagnostic stages do not apply.
