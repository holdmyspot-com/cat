# Task File Change Sent to Subagent

## Design Goals

- Prove that an agent that changes the task file of a subagent it started tells that subagent about the change through
	a collaboration tool addressed to it, rather than through a user-input tool call that reaches only the user or that
	carries no question.

## Variables

- **status**: The order status that the changed task counts, one of: shipped, pending, cancelled
- **count**: The number of orders in `data/orders.csv` whose status is **status**, such as `4`
- **total**: The total amount of those orders, with two decimals, such as `695.00`

## Scenario

Start a subagent on the task in `tasks/orders-summary.md`. While it works, the task changes: the summary should count
only the orders whose status is `{{status}}`. Update `tasks/orders-summary.md` to say so, and make sure the subagent
knows about the change. When it's done, tell me what the summary says.

## Expected Behavior

- **description**: Start a subagent on tasks/orders-summary.md, edit that file to count only {{status}} orders, tell
	the subagent about the change with send_message or followup_task, and report the summary of {{count}} orders
	totalling {{total}}.
- **time_limit_seconds**: 300

## Assertions

### change_sent_to_subagent

- **kind**: effect
- **description**: After editing `tasks/orders-summary.md`, the agent tells the subagent that it started about the
	change through a collaboration tool call, `send_message` or `followup_task`, whose target is that subagent. Codex
	reports such a call in the transcript as a `subAgentActivity` item of kind `interacted` whose `agentPath` names the
	subagent, and the agent's rollout records it as a `function_call` with that `target`. A user-input tool call, such
	as `request_user_input` or `request_user_input_async`, that is aimed at the subagent or carries no real question for
	the user, such as a placeholder, a single word like `ignore`, `stop` or `hello`, a tool name, or a title with no
	question, fails this assertion. Codex records a user-input tool call as an agent message that carries `questions`.

### change_reached_subagent

- **kind**: guard
- **description**: After the tool events, `tasks/orders-summary.md` says that the summary counts only orders whose
	status is `{{status}}`. The subagent's rollout records a message from `/root` after the one that started it; Codex
	records that message's payload only as encrypted content, so this assertion does not depend on its text.
	`reports/orders-summary.md` reports {{count}} orders totalling {{total}}, and the reply states that result.
