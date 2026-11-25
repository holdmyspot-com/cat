# Claude sandbox-start recordings

Streams that Claude Code 2.1.332 printed with `--print --output-format stream-json --verbose` on 2026-11-24 (UTC), model
`haiku`, `--effort low`, in a disposable project directory. Each session's settings held `sandbox.enabled: true`,
`sandbox.allowUnsandboxedCommands: false` and `permissions.allow: ["Bash"]`, and no `CLAUDE_CODE_SUBPROCESS_ENV_SCRUB`.
The prompt asked for three Bash commands in one step and a fourth after their output. Each command printed a marker
(`MARKER_ALPHA`, `MARKER_BRAVO`, `MARKER_CHARLIE`, `MARKER_DELTA`), `SANDBOX_RUNTIME` (`1` inside Claude Code's sandbox,
`unset` outside it) and whether it could create a file outside the project (`OUTSIDE_WRITE_OK_...` outside the sandbox).
Local paths are replaced by `<project>`, `<home>`, `<config>`, `<outside>`, `<tmp>`, `<run>` and `<host-home>`. The
recordings hold no `session_state_changed` lines, because those sessions did not set
`CLAUDE_CODE_EMIT_SESSION_STATE_EVENTS`; a test that replays them supplies those lines.

- `sandbox-started.jsonl`: a session whose sandbox started (a 17-byte `TMPDIR`). All four results carry
  `SANDBOX_RUNTIME=1`, and no result reports a sandbox failure.
- `bridge-sockets-report-then-commands-ran.jsonl`: a session whose `TMPDIR` was exactly 80 bytes, which is too long for
  Claude Code's bridge sockets, with no process stopping it. The result of the first Bash call is the report
  `Sandbox is enabled but failed to initialize: Failed to create bridge sockets after 5 attempts. Sandboxing is disabled
  for the rest of this session; restart to retry.` The results of the other calls follow it with
  `SANDBOX_RUNTIME=unset`: they ran outside the sandbox.
- `bridge-sockets-report-stopped.jsonl`: the same session stopped as soon as the report line was read. The stream ends
  after the report.
- `socket-listen-report-then-commands-ran.jsonl`: a session started in a shell whose `socket(AF_UNIX, ...)` calls fail
  with `EPERM`, with no process stopping it. The report has the same frame with another reason:
  `EPERM: operation not permitted, listen '<tmp>/srt-mux-16-2.sock'`. Results that follow it ran outside the sandbox.
- `socket-listen-report-stopped.jsonl`: the same session stopped as soon as the report line was read.
