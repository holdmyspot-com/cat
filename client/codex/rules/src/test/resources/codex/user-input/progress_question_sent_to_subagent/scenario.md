# Progress Question Sent to Subagent

## Design Goals

- Prove that an agent whose wait for a subagent ends before the subagent finishes asks that subagent about its
	progress through a collaboration tool addressed to it, rather than through a user-input tool call that reaches only
	the user or that carries no question.

## Variables

- **interval**: How many seconds the agent waits for the subagent before asking how far it has got, one of: 20, 30, 45

## Scenario

Have a subagent run `sh scripts/verify-backups.sh`, which takes a few minutes, and report which backups it verified.
Don't wait on it for more than {{interval}} seconds at a time: whenever it hasn't finished by then, ask it how far it
has got. When it's done, tell me which backups were verified and which were damaged.

## Expected Behavior

- **description**: Start a subagent on sh scripts/verify-backups.sh, wait for it at most {{interval}} seconds at a
	time, ask it for its progress with send_message or followup_task after each wait that ends before it finishes, and
	report that monday, tuesday, thursday and friday were verified and wednesday was damaged. Do not edit files.
- **time_limit_seconds**: 420

## Assertions

### progress_asked_of_subagent

- **kind**: effect
- **description**: After a `wait_agent` call that returns before the subagent finishes, which its rollout result shows
	as `"timed_out":true`, the agent asks the subagent about its progress through a collaboration tool call,
	`send_message` or `followup_task`, whose target is that subagent. Codex reports such a call in the transcript as a
	`subAgentActivity` item of kind `interacted` whose `agentPath` names the subagent, and the agent's rollout records it
	as a `function_call` with that `target`. A user-input tool call, such as `request_user_input` or
	`request_user_input_async`, that is aimed at the subagent or carries no real question for the user, such as a
	placeholder, a single word like `ignore`, `stop` or `hello`, a tool name, or a title with no question, fails this
	assertion. Codex records a user-input tool call as an agent message that carries `questions`.

### verification_reported

- **kind**: guard
- **description**: The subagent runs `sh scripts/verify-backups.sh` to its end, no file is changed, and the reply says
	that the backups monday, tuesday, thursday and friday were verified and that wednesday was damaged.
