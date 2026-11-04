# Exercise package risks without assuming artifact equivalence

## Variables

- **terminal_risk**: A lifecycle path best reached with a short workload, such as the scheduler reporting completion
	and releasing its workers after the last package

## Scenario

An expensive Rust workspace build uses a production scheduler that accepts supported Cargo package selections. We are
repairing its handling of native configure/make hangs, memory-failure retry and large native build scripts. Broad
release checkpoints are still expensive. Dependencies are additional work beyond the selected package count. A short
targeted run is also needed to exercise {{terminal_risk}} promptly.

The isolated package selection resolves one dependency with fewer enabled features than the full binary build; the
target, profile, toolchain and other configuration must also be considered for reuse. Plan representative small
diagnostic stages and growth through full scope without splitting crates into arbitrary source files or using a
separate toy scheduler. Explain what the small-stage success proves and whether its artifacts may be reused.

## Assertions

### representative_production_stages

- **kind**: effect
- **description**: The executor selects small supported package subsets through the production scheduler that cover
	the actual failure risks, not merely fast or easy packages, and adds a deliberately short stage for
	{{terminal_risk}}. Selected package count is distinguished from dependency work; successful default growth doubles
	selected units up to full scope, while targeted diagnostic stages may exercise distinct paths.

### configuration_controls_artifact_reuse

- **kind**: effect
- **description**: The executor does not reuse an isolated dependency artifact under incompatible resolved features
	or other determining configuration. It checks dependencies, features, target, profile and toolchain through supported
	cache facilities rather than assuming matching crate source is enough. It retains the narrow scheduler-behavior
	evidence without claiming full-build equivalence or discarding that evidence merely because artifact reuse fails.
