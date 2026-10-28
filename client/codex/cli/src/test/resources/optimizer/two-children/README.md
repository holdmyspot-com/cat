# Codex trial with two children

This directory holds a trial's run artifacts laid out as the runner keeps them: [transcript.jsonl](transcript.jsonl)
holds the app-server notifications, and [session-transcripts](session-transcripts) holds one rollout for each thread.
They come from a prompt-optimizer trial run `mvqxchqv-24befule1u`, recorded on 2026-10-27 with codex-cli `0.160.0`
(`thread/started` and every rollout's `session_meta` report it) and model `gpt-6-luna` at effort `low`.

The trial's prompt was:

> Where does Acme ship orders from, and what is the chemical symbol for sodium?

The prompt under test told the root to start one child per question, wait for both children's findings, then answer.
The root read `docs/facts.md`, started the children `/root/shipping` and `/root/sodium`, called `list_agents`, called
`wait_agent` once with only `timeout_ms`, received both children's `FINAL_ANSWER` messages, then answered and
appended a note with one more command.

What the recording establishes:

- The app-server reports each start as a `subAgentActivity` item of kind `started` with the child's thread and task
  path, and each child's end as one of kind `completed`. The assignment that the root sent is not in any app-server
  notification.
- The `wait` item reports `receiverThreadIds: []` and `agentsStates: {}`, both when it starts and when it completes.
- Only the root rollout records what the root received: the `wait_agent` result `Wait completed.`, then one
  `agent_message` per child whose `input_text` holds the child's finding. Each such message follows the
  `item_completed` event of an app-server item; here, the `wait`.
- The `spawn_agent` call's `message` argument and the child rollout's `NEW_TASK` message hold the assignment only as
  `encrypted_content`; its task name is the only plaintext.

Selection and substitutions:

- `transcript.jsonl` keeps, unchanged and in order, every `thread/started`, `turn/started`, `turn/completed`,
  `item/started` and `item/completed` notification: 37 of the source's 177 lines. Deltas, token usage, rate limits,
  thread status, moderation metadata, the remote-control status, `thread/settings/updated` and the response to
  `turn/start` are dropped.
- Each rollout keeps, unchanged and in order, its `session_meta` line, the owning thread's `event_msg`
  `item_completed` lines and the `response_item` lines whose payload is a `function_call`, `function_call_output`,
  `custom_tool_call`, `custom_tool_call_output` or `agent_message`. The `session_meta` line is re-serialized with its
  `base_instructions` text replaced by `<omitted>`.
- The run directory is replaced with `/recorded/run` and the project that held the campaign with `/recorded/project`,
  throughout the text. Thread, turn and item identifiers, timestamps and encrypted values are unchanged.
