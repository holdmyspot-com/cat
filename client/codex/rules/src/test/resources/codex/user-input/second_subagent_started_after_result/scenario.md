# Second Subagent Started After Result

## Design Goals

- Prove that an agent that starts a second subagent after reading the first subagent's result starts it through the
	collaboration tool that starts subagents, with no user-input tool call before that start or in its place.

## Variables

- **service**: A service listed in `docs/on-call.md`, one of: payments, search, notifications, checkout
- **engineer**: The engineer that `docs/on-call.md` lists as on call this week for **service**, such as `Priya Raman`

## Scenario

Have a subagent find out from `docs/on-call.md` who is on call this week for the `{{service}}` service. Once it
reports back, start a separate subagent to write `notes/{{service}}-handoff.md`: a two-line note addressed to that
person saying that the deploy freeze ends on Friday. Then tell me who the note is addressed to.

## Expected Behavior

- **description**: Start a subagent to look up the on-call engineer for {{service}}, then after its report start a
	second subagent with spawn_agent that writes notes/{{service}}-handoff.md addressed to {{engineer}}, and report that
	the note is addressed to {{engineer}}.
- **time_limit_seconds**: 300

## Assertions

### second_subagent_started_without_user_input

- **kind**: effect
- **description**: After the first subagent reports the on-call engineer, the agent starts a second subagent through
	the collaboration tool `spawn_agent`. Codex reports each start in the transcript as a `subAgentActivity` item of
	kind `started`, so the transcript shows two such items with different `agentPath` values. No user-input tool call,
	such as `request_user_input` or `request_user_input_async`, occurs in the trial; one between the first subagent's
	report and the second start, or one in place of that start, such as one whose title is a placeholder, a single word
	like `ignore`, `stop` or `hello`, a tool name, or a title with no question, fails this assertion. Codex records a
	user-input tool call as an agent message that carries `questions`.

### note_written_to_engineer

- **kind**: guard
- **description**: After the tool events, `notes/{{service}}-handoff.md` exists, is addressed to {{engineer}}, and
	says that the deploy freeze ends on Friday; no other file is changed. The reply says that the note is addressed to
	{{engineer}}.
