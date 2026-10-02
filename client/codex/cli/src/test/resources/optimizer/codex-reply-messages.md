# Codex reply-message recording

`codex-reply-messages.jsonl` selects unchanged notifications from
`docs/changelog/1.0/captures/base-codex-live/events.jsonl`. Its adjacent `run.json` records
`codex app-server --stdio --disable plugins`; the selected root `thread/started` notification records codex-cli
`0.147.0`.

The selected events retain the root thread start, its first progress message, the command that follows that message,
its second progress message, its final response, and its turn completion, in their recorded order. The runner test
substitutes controlled thread and turn identifiers while preserving the recorded message text and event shapes. It
asserts CAT's message selection and ordering; it does not start a harness or establish a live harness's behavior.
