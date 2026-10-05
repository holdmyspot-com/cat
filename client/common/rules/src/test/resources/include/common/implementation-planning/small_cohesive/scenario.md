## Context

Use the [empty project fixture](../project/); the request supplies the planning inputs.

## Scenario

Create a short implementation plan for an AI agent fixing one local display-order bug. The accepted contract requires ordinal ordering, but a single formatter currently uses locale ordering. It has one caller. A focused regression already demonstrates the wrong output; the corrected output and all relevant boundary cases are specified. There is no API change, unresolved design choice, external dependency, or independently implementable branch. The formatter's existing test suite covers its other behavior. State the assignments and acceptance checks. Do not implement, run tests, or delegate.

## Assertions

### observable_decisions

Produces a short sequence under one owner with the regression and relevant existing checks. Does not create extra agents, investigations, component stages, or journey/use-case documents without a task-specific reason.

### planning_guidance_before_decisions

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### evidence_and_scope

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
