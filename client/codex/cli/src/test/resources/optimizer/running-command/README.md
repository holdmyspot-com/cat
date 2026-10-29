# Codex trial whose child received a command's result while the command was still running

This directory holds a trial's run artifacts laid out as the runner keeps them: [transcript.jsonl](transcript.jsonl)
holds the app-server notifications, and [session-transcripts](session-transcripts) holds one rollout for each thread.
They come from the prompt-optimizer trial run `mvto9hom-48cde6agu`, recorded on 2026-10-29 with codex-cli `0.160.0`
(`thread/started` and every rollout's `session_meta` report it) and model `gpt-6-luna`.

The trial's prompt was:

> Where does Acme ship orders from, and what is the capital of Australia?

The root read `docs/facts.md`, started the children `/root/acme_shipping` and `/root/australia_capital`, and waited for
them until the trial's time limit. Each child read `docs/facts.md` with `cat docs/facts.md` and reported a finding.

What the recording establishes:

- The child `/root/acme_shipping` (thread `01a1882f-73ef-7ad1-bb1c-17227c5bb14e`) called `exec` with the call id
  `call_9259c56917eb4e79ad585af16683285c` at 15:03:15.085Z. The app-server reports the command
  `exec-5d6000ed-6340-4c1f-a443-3f0951c17d4b` starting at 15:03:15.254Z (`startedAtMs` 1793286195254).
- The child's rollout records that call's `custom_tool_call_output` at 15:03:25.257Z as
  `Script completed\nWall time 10.2 seconds\nOutput:\n` followed by an empty item: no output and no exit status.
- Only afterwards, at 15:03:32.436Z, the rollout records the command's `item_completed` event, and the app-server
  reports its `item/completed`, with exit code 0, `durationMs` 17181 and the file's content as `aggregatedOutput`. The
  child's final answer says that `docs/facts.md` returned no output.
- Neither the app-server item nor the rollout's `item_completed` event names the call id that started the command, and
  the call's result names no command item.
- The root's command and the other child's command ended before their calls' results, which hold the file's content.

Selection and substitutions:

- `transcript.jsonl` keeps, unchanged and in order, every `thread/started`, `turn/started`, `turn/completed`,
  `item/started` and `item/completed` notification: 51 of the source's 217 lines. Deltas, token usage, rate limits,
  thread status, moderation metadata, the remote-control status, `thread/settings/updated` and the responses to
  requests are dropped.
- Each rollout keeps, unchanged and in order, its `session_meta` line, the owning thread's `event_msg`
  `item_completed` lines and the `response_item` lines whose payload is a `function_call`, `function_call_output`,
  `custom_tool_call`, `custom_tool_call_output` or `agent_message`. The `session_meta` line is re-serialized with its
  `base_instructions` text replaced by `<omitted>`.
- The run directory is replaced with `/recorded/run` and the project that held the campaign with `/recorded/project`,
  throughout the text. Thread, turn, call and item identifiers, timestamps and encrypted values are unchanged.
