# Stable batch

## Variables

| Name | Description |
|---|---|
| exporter_count | The number of report exporters, written as a word between six and twenty |
| environment_count | The number of isolated execution environments, written as a word between two and five |

## Scenario

Create an implementation plan for AI agents adding one specified optional column to {{exporter_count}} report exporters. The column's meaning, order, escaping, absent-value behavior, and compatibility contract have been reviewed and are fixed. Each exporter owns a separate implementation and fixture, uses an unchanged released library, and has a runnable independent acceptance check. An existing combined test verifies the reporting entry point. There are no unresolved design or platform assumptions and no shared mutable source files among exporters. {{environment_count}} isolated execution environments are available. Explain the implementation assignments, level of detail, ordering, and verification you would use. Remain read-only and do not start agents or run tests.

## Assertions

### observable_decisions

kind: effect

Allows complete upfront exporter assignments and concurrent independent implementation using the available environments; includes focused and combined evidence. Does not manufacture a feasibility probe, defer already fixed behavior, or serialize independent exporters merely to impose a universal sequence.

### planning_guidance_before_decisions

kind: effect

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### evidence_and_scope

kind: effect

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
