## Context

Use the [empty project fixture](../project/); the request supplies the planning inputs.

## Scenario

Create an implementation plan for this quality-assessment service. The approved product claim is a one-sided
exact binomial lower confidence bound above 0.95 for each material category, evaluated once at a predeclared
fixed sample size. Its statistical routine is valid for independent identically distributed Bernoulli trials
within a category. Each category contains several approved test definitions with potentially different success
probabilities. The draft sampling component independently draws each test's incidental values but selects
exactly the same number of trials from every test definition. It returns a list of binary outcomes and the
statistics component pools those outcomes as one binomial sample. All entries satisfy the result schema. The
product permits choosing the test-selection protocol before user confirmation; the final protocol and
probabilities must be shown in that proposal, and none may adapt to observed outcomes. Plan the implementation
and acceptance boundaries. Remain read-only: do not implement, execute probes or tests, or delegate.

## Assertions

### population_compatibility

The plan recognizes that fixed per-definition counts with potentially different success probabilities do not establish
the consumer's IID Bernoulli premise. A valid binary result schema is insufficient.

### supported_correction

It resolves the handoff before declaring statistical work ready: for example, independently draw test definitions from
a frozen distribution with replacement, or establish a different valid procedure for the required claim. It retains
fixed stopping, probabilities confirmed before outcomes, and a consumer-boundary check. It does not claim planned tests
ran or that fresh incidental wording proves independence.

### planning_guidance_before_decisions

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### evidence_and_scope

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
