# Result Follow-up Sent to Subagent

## Design Goals

- Prove that an agent that must ask a subagent a follow-up question after the subagent reports its result sends that
	question through a collaboration tool addressed to the subagent, rather than through a user-input tool call that
	reaches only the user or that carries no question.

## Variables

- **version**: A version listed in `docs/CHANGELOG.md` that has an earlier version, one of: 3.4.0, 3.3.2, 3.3.1, 3.3.0
- **date**: The release date of **version** in `docs/CHANGELOG.md`, such as `2026-09-14`
- **previous_version**: The version listed directly below **version** in `docs/CHANGELOG.md`, such as `3.3.2`
- **previous_date**: The release date of **previous_version** in `docs/CHANGELOG.md`, such as `2026-08-02`

## Scenario

Have a subagent look up in `docs/CHANGELOG.md` when version {{version}} was released. Once it reports back, ask that
same subagent which version came out just before it and when, then tell me both answers.

## Expected Behavior

- **description**: Start a subagent to find the release date of {{version}}, then after its report send it the
	follow-up question with followup_task or send_message, and report {{version}} released on {{date}} and
	{{previous_version}} released on {{previous_date}}. Do not edit files.
- **time_limit_seconds**: 240

## Assertions

### followup_sent_to_subagent

- **kind**: effect
- **description**: After the subagent reports the release date of {{version}}, the agent asks it about the previous
	version through a collaboration tool call, `followup_task` or `send_message`, whose target is that subagent. Codex
	reports such a call in the transcript as a `subAgentActivity` item of kind `interacted` whose `agentPath` names the
	subagent, and the agent's rollout records it as a `function_call` with that `target`. A user-input tool call, such
	as `request_user_input` or `request_user_input_async`, that is aimed at the subagent or carries no real question for
	the user, such as a placeholder, a single word like `ignore`, `stop` or `hello`, a tool name, or a title with no
	question, fails this assertion. Codex records a user-input tool call as an agent message that carries `questions`.

### both_answers_reported

- **kind**: guard
- **description**: The evidence shows two messages that the agent received from the same subagent, the second after
	the follow-up, and the reply says that {{version}} was released on {{date}} and that {{previous_version}}, released
	on {{previous_date}}, came out just before it. No file is changed.
