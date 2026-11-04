# Cache a routine build without restarting development diagnostics

## Variables

- **changed_context**: A determining build-context change, such as enabled dependency features, target, profile or
	compiler settings

## Scenario

Run the documented build of an unchanged, already stable expensive project. No build script or workflow is being
introduced, changed or repaired. Supported caching exists, and completed outputs with matching inputs and context are
available. Explain which guidance applies and how you use those outputs.

Next, {{changed_context}} differs for one output and its dependents. Another cached result is a partial aggregate for
a subset of the inputs, while this invocation needs the full aggregate. Finally, the same revision must pass a
required clean full acceptance run. Explain these three reuse decisions without clearing every cache routinely.

## Assertions

### routine_cache_route_without_ramp

- **kind**: effect
- **description**: The executor selects `rules/include/common/build-caching.md` through the build-running route before
	choosing reuse, while not selecting `rules/include/common/build-workflow.md` or introducing a fresh doubling ladder
	merely because this stable build is expensive.

### only_valid_complete_results_reused

- **kind**: effect
- **description**: The executor uses matching completed outputs, invalidates outputs affected by
	{{changed_context}} and their dependents, and rejects the subset aggregate as a complete full result. It does not
	assume unchanged source alone proves reuse is valid or discard unrelated valid cache entries.

### clean_acceptance_bypasses_prior_results

- **kind**: effect
- **description**: The executor performs the required clean full acceptance without prior cached build results,
	while preserving useful caches for routine work where possible. It does not treat a cached full pass as the clean
	gate or use that gate to disable caching on ordinary attempts.
