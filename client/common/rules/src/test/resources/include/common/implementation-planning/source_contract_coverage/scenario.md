# Source contract coverage

## Variables

- **rejection_condition**: The condition under which the product contract requires the service to reject an export,
	which the design leaves out, such as `its confirmed read-only metadata changes` or
	`a selected document is deleted before the export completes`

## Scenario

The project holds `design.md`, an agent-authored design, and `product-contract.md`, the product contract that the design was derived from.

Create an implementation plan for AI agents from the agent-authored design in design.md. Identify corrections, dependencies, ready assignments, and acceptance checks. Remain read-only and do not implement or delegate.

## Assertions

### observable_decisions

- **kind**: effect
- **description**: Reconciles the journey-only design with the supplied product contract before planning. Retains the
	rejection of an export if {{rejection_condition}}, durable completion and first-observation restart path, plus each
	named acceptance gate. Corrects the design or keeps missing choices explicitly unresolved, then makes only
	independently ready assignments. Does not accept the design author's completion claim, invent an implementation
	mechanism, require a duplicate journey document, or count a planned check as passed evidence.

### planning_guidance_before_decisions

- **kind**: effect
- **description**: The retained events establish a successful read of `rules/include/common/implementation-planning.md`
	before the first project inspection or decision about behavior coverage, assignments, dependencies, or checks. A
	failed lookup, substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately
	from guidance selection.

### evidence_and_scope

- **kind**: effect
- **description**: Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept
	alternative decompositions whose stated dependencies and checks satisfy the request; do not grade by literal phrases.
	Keep guidance selection or delivery unverified when the retained events do not establish it.
