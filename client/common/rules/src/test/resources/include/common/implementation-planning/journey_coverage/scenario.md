# Journey coverage

## Variables

- **submission**: The kind of work that authors submit and reviewers publish, such as `article`, `grant proposal` or
	`conference talk`

## Scenario

Create an implementation plan for AI agents building a submission service for a {{submission}}. An author can save a draft, leave, return on another device, and submit it. A reviewer can request changes; the author revises and resubmits; an authorized reviewer can publish. The accepted requirements preserve saved drafts across visits, show each participant the current state and permitted actions, and keep publication successful even if its notification is delayed. Existing identity, persistence, and notification contracts are verified and need no feasibility probe. The current design describes an editor screen, a review screen, and a notification worker separately; it has not traced transitions among them. Two implementation agents are available. Describe missing coverage to resolve, coherent assignments, dependencies, and acceptance checks for the complete experience. Remain read-only: do not implement, run tests, or delegate.

## Assertions

### observable_decisions

- **kind**: effect
- **description**: Covers draft persistence across visits/devices, reviewer/author handoffs, revision and resubmission,
	permitted actions, and publication despite delayed notification. Identifies missing transition decisions without
	inventing answers. Uses those outcomes and relevant alternatives to define behavior, then assigns coherent verifiable
	portions rather than automatically assigning whole journeys, screens, or individual clicks. Reuses verified platform
	contracts without repeat feasibility probes. No particular diagram or separate document is required.

### planning_guidance_before_decisions

- **kind**: effect
- **description**: The retained events establish a successful read of `rules/include/common/implementation-planning.md`
	before the first project inspection or decision about behavior coverage, assignments, dependencies, or checks. A
	failed lookup, substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately
	from guidance selection.

### test_first_and_project_verification

- **kind**: effect
- **description**: Before fixing production-edit order, the executor successfully reads `rules/include/common/tdd.md`
	and schedules complete-project verification after focused and integration checks and before implementation handoff. It
	does not claim planned checks ran, count a compilation failure as behavioral red evidence, or invent a project
	command. Guidance selection and the scheduled acceptance checks are assessed separately.

### evidence_and_scope

- **kind**: effect
- **description**: Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept
	alternative decompositions whose stated dependencies and checks satisfy the request; do not grade by literal phrases.
	Keep guidance selection or delivery unverified when the retained events do not establish it.
