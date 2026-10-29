# Codex root that asked the user for input

[transcript.jsonl](transcript.jsonl) holds app-server notifications for two root turns. In each turn, the root asked
the user for input through a user-input tool call (`request_user_input_async`), which Codex records as an agent
message that carries `questions`:

- The first call asks a complete question and offers two answers.
- The second is a stray call whose only question is the one-word title `Ignore` with no options. The agent made it
  while it meant to notify a subagent.

## Source

No recorded app-server stream holds an agent message whose `questions` is not `null`, so these notifications are
derived rather than recorded. They come from two `event_msg` `item_completed` lines of an interactive Codex session's
rollout (`rollout-2026-10-24T21-58-32-01a170c7-eade-7431-b337-01b356b20b2d.jsonl`, codex-cli `0.160.0`, model
`gpt-6.1-sol` at effort `medium`). In that rollout, each line follows a `request_user_input_async` `function_call`
with the same questions:

- Ordinal 20492, 2026-10-25T17:53:21.426Z, turn `01a1741d-e545-7330-a5aa-3a9e23e73d52`: item
  `call_537fc2e50a674e43b018857d10cf1c71`.
- Ordinal 109313, 2026-10-29T16:34:20.251Z, turn `01a18881-2608-7c63-adda-19d76b15d5ec`: item
  `call_52021ae983ac4778914d8151e19b6de4`.

The rollout's item has the type `AgentMessage`, its text as a `content` array of `Text` items, and the fields `phase`,
`delivery` and `questions`, whose entries have a `title` and an `options` array or `null`.

## Derivation

- Each rollout item becomes an `item/started` and `item/completed` pair in the camelCase `agentMessage` shape that the
  recorded app-server stream `../sessions/trial-turn-1.jsonl` shows: `type`, `id`, `text`, `phase`,
  `memoryCitation`, `delivery` and `questions`. The `item/started` text is empty, as in that recording.
- The item's `id`, `phase`, `delivery` and `questions` are copied unchanged. The `text` is the item's `content` text
  joined. `memoryCitation` is `null`, as in the recording.
- The thread and turn identifiers are the rollout's. Each `startedAtMs`, `completedAtMs` and `emittedAtMs` is the
  rollout item's `started_at_ms` or `completed_at_ms`.
- Each pair is enclosed in a `turn/started` and `turn/completed` notification in the shape of that recording, with
  the turn's times set to `null`.
- The rollout's other lines are not copied, and no `session_meta`, instruction text, encrypted value or
  `thread/started` notification is included. Without `thread/started`, the parser names the root from the first
  `turn/started`.
