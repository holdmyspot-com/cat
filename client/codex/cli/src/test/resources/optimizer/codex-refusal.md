# Codex refusal recording

`codex-refusal.jsonl` selects unchanged notifications from one optimizer trial session's `artifacts/turn-1.events`,
recorded on 2026-10-06 by codex-cli `0.160.0` (the `cliVersion` of its root `thread/started` notification). The run's
`run.json` records `codex app-server --stdio --enable hooks`; Codex reached the provider through a load balancer at
`127.0.0.1:2455`.

The selected events retain the turn start, two of the nine `error` notifications that Codex retried
(`willRetry` true, `codexErrorInfo.responseStreamDisconnected.httpStatusCode` 403), the `warning` between them that
reports the fallback from WebSockets to HTTPS, the final `error` notification that Codex did not retry (`willRetry`
false, `codexErrorInfo.httpConnectionFailed.httpStatusCode` 403), and the failed turn completion, in their recorded
order. The tests assert how CAT classifies these events; they do not start a
harness or establish a live harness's behavior.
