## Context

Use the supplied [project fixture](fixtures/source-contract-project/), including its
[design](fixtures/source-contract-project/design.md) and [product
contract](fixtures/source-contract-project/product-contract.md).

## Scenario

Create an implementation plan for AI agents from the agent-authored design in design.md. Identify corrections, dependencies, ready assignments, and acceptance checks. Remain read-only and do not implement or delegate.

## Assertions

### observable-decisions

Reconciles the journey-only design with the supplied product contract before planning. Retains the changed-metadata rejection, durable completion and first-observation restart path, plus each named acceptance gate. Corrects the design or keeps missing choices explicitly unresolved, then makes only independently ready assignments. Does not accept the design author's completion claim, invent an implementation mechanism, require a duplicate journey document, or count a planned check as passed evidence.

### planning-guidance-before-decisions

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### evidence-and-scope

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
