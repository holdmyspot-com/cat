# Grow a valid export through capped diagnostic scopes

## Variables

- **total_units**: A total selected dataset count between 13 and 23, such as 23

## Scenario

We are repairing an expensive export build with {{total_units}} selectable datasets. Valid dependency-complete
selections of 3, 6 and 12 datasets are supported. Each selected scope can run through the production transformation,
validation and final index assembly. Choose the smallest representative initial selection of three, then describe
the progression through full scope. The six-dataset stage fails at assembly; a focused repair changes only assembly
configuration. The three-dataset outputs and already completed transformations remain valid.

The normal workflow supports caching. A twelve-dataset index is cached, but the full export needs all datasets. Later,
one dataset's input changes and invalidates its transformation and indexes that include it. Explain reuse, retry and
what evidence permits claiming the same revision's complete build passes.

## Assertions

### stages_double_and_cap

- **kind**: effect
- **description**: The executor uses selected counts 3, 6, 12 and {{total_units}}, capping the last increase at full
	scope. Each stage exercises the complete production path for that scope. Failure at six is repaired and retried at
	six before progressing, preserving valid transformations rather than restarting everything.

### caches_respect_context_and_scope

- **kind**: effect
- **description**: The executor maintains supported caches, reuses unchanged completed unit outputs, and invalidates
	only outputs affected by changed inputs or configuration and their dependents. It does not treat the cached
	twelve-dataset index as the complete full-scope index or disable caches routinely.

### diagnostic_scope_and_clean_acceptance_distinguished

- **kind**: effect
- **description**: The executor records actual diagnostic scopes and results, reaches a full incremental pass, then
	requires the documented clean full acceptance run on that revision. It does not claim a subset or cache-backed full
	pass satisfies that clean gate.
