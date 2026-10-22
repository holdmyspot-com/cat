# Codex tool-call recordings

`codex-tool-calls.jsonl` contains unchanged notifications selected from a real app-server session recorded on
2026-10-01 with codex-cli `0.159.1+23` (`codex --version`). The session started with:

```
codex app-server --stdio --enable multi_agent -c 'openai_base_url="http://127.0.0.1:2455/backend-api/codex"' -c 'model="gpt-6-luna"' -c 'model_reasoning_effort="low"'
```

The isolated `CODEX_HOME` contained a copy of the host's `auth.json`; no credential values were recorded in the
fixture. The provider URL above came from the host's `openai_base_url` setting. The isolated home otherwise used
Codex defaults. The JSON-RPC client initialized with `experimentalApi: true`, sent `initialized`, and opened a
`workspace-write` thread with `approvalPolicy: never` and model `gpt-6-luna`, then sent `turn/start` with effort `low` and this prompt:

> Run true and false as two separate exec_command calls, in that order. Do not combine them. Then use spawn_agent to
> ask a child to run true once and report done, and wait for the child to finish. Do not edit files. Reply done.

The recording directory and isolated `CODEX_HOME` were under `.cat/work/temp/tool-events-recording`; its scratch files
were deleted after extraction. The session completed. Both root command items and the child's command item reported
exit code 1 because the command sandbox rejected the app-server socket directory's permissions. The parser tests
assert the reported status rather than treating `true` as successful by inference. The root's `wait` call has no
command or exit code. `subAgentActivity` records child activity, while the child's command notifications have their
own `threadId`; neither is a root tool call.

`codex-command-success.jsonl` contains the unchanged root `thread/started` notification and the first command's
`item/started` and `item/completed` notifications from the repository's existing recording at
`docs/changelog/1.0/captures/base-codex-live/events.jsonl`. Its adjacent `run.json` records the native source command
`codex app-server --stdio --disable plugins`; the thread notification records codex-cli `0.147.0`. The command reports
exit code 0. Its shell wrapper is deliberately preserved, because the parser must retain the command the harness
reports rather than reconstruct it from `commandActions`.

`codex-file-change.jsonl` selects the root notification and the two `fileChange` notifications from that same
`0.147.0` recording. Neither notification reports a command or exit code.

`codex-web-search.jsonl` selects unchanged root and web-search notifications from
`docs/changelog/1.0/captures/reviews/languages/php/codex-base-structured.events.jsonl`. Its adjacent `.run.json`
records `codex app-server --stdio --disable plugins`; its root notification reports codex-cli `0.147.0`.

`codex-dynamic-tool.jsonl` contains unchanged root and dynamic-tool notifications from another live recording on
2026-10-01 with codex-cli `0.159.1+23`, started with:

```
codex app-server --stdio -c 'model="gpt-6-luna"' -c 'model_reasoning_effort="low"'
```

The isolated home copied the host `auth.json` and configured the same `openai_base_url` as above. It also configured
an MCP server named `recording`, started with `python3` and a local script. That script answered the MCP initialization,
tool-list and call requests with a read-only `echo` tool and the text `done`. The server reported ready, but this
recording contains no `mcpToolCall` item. The `thread/start` request instead exposed this dynamic tool:

```
{"type":"function","name":"recording_echo","description":"Trivial local recording probe: returns done, no side effects.","inputSchema":{"type":"object","properties":{},"additionalProperties":false}}
```

The `turn/start` prompt was:

> Call the MCP recording echo tool once, then call the dynamic recording_echo tool once, then call clock.sleep with
> duration_ms 1. Do not run commands or edit files. Reply done.

The client answered `item/tool/call` with `contentItems: [{type: "inputText", text: "done"}]` and `success: true`.
The recording establishes that the emitted dynamic call has `tool: "recording_echo"` and a success flag, with no
command or exit code. It establishes no MCP or sleep output. The session completed; its scratch directory was deleted
after extraction.

The supported tool-item inventory was checked using
`codex app-server generate-json-schema --out <scratch-directory> --experimental` on `0.159.1+23`.
The schema's `ThreadItem` alternatives classify command execution, file changes, MCP calls, dynamic calls,
collaboration calls, web searches, image views, sleep, and image generation as tool items. The schema names MCP and
dynamic tools with a `tool` field. No repository recording or the additional live recording contains an MCP, image
view, sleep or image-generation item, so their fields have no recording-backed assertions here.
