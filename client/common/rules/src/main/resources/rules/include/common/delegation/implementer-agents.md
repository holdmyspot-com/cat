# Delegation: Implementer Agents

## Design Goals

- When a parent chooses which of CAT's implementer agents runs a delegated implementation task, select the lowest tier
	whose result the task's build, test or diff check can accept, given the decisions the task contract still leaves to
	the agent, so that a task whose content the contract already fixes does not run on a higher tier.
- When a delegated result fails its acceptance check because the task needed a decision its contract did not settle,
	settle that decision and resend the task, so that a contract gap is not hidden by moving the task to a higher tier.

## Guidance

CAT ships four implementer agents in each harness's plugin. Their names end in `optimize-low`, `optimize-medium`,
`optimize-high` and `optimize-xhigh`, in order of increasing cost and capability; each harness's agent definitions set
the model and reasoning effort behind each name. Choose the lowest tier that fits what the task contract still leaves
to the agent:

- `optimize-low`: the contract states each edit's location and content, for example a ready patch to apply, a rename,
	a changed configuration value or path, or a version bump whose expected value a test reports.
- `optimize-medium`: the contract settles every choice, but the agent must find the places that a settled change
	reaches, such as every caller of a decided interface, or repair failures that its own gate reports.
- `optimize-high`: the contract leaves only internal choices to the agent, such as a helper's structure, a type's
	layout or the arrangement of the tests it lists, which change nothing a user or a documented interface can observe.
- `optimize-xhigh`: the task keeps high-judgment work, or accepting its result would repeat the task's analysis, and
	the parent delegates it rather than doing it itself.

When a result fails its acceptance check because the task needed a decision that its contract did not settle, settle
that decision and resend the task to the tier that the completed contract selects. Move the task to a higher tier only
when that decision cannot be settled before the agent works.
