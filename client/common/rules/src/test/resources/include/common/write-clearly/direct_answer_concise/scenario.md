# Direct answer concise

## Variables

- **agents**: The agents in CAT's installed inventory, none of which implements code, such as
	`read-only plan-builder, plan-review, and stakeholder-review agents`

## Scenario

Answer this question for a developer who wants only the current product fact:

> Does CAT contain an implementation subagent?

CAT's installed agent inventory contains {{agents}}. The host
harness separately provides a generic worker agent.

## Assertions

### direct_answer_is_concise

- **kind**: effect
- **description**: The answer leads with “No,” distinguishes CAT's agents from the harness worker, and does not add
	implementation history, an unrelated feature list, or multiple restatements of the conclusion.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`
	before its first governed action.
