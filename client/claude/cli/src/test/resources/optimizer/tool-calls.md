# Claude tool-call recordings

These fixtures are excerpts of Claude Code 2.1.287 stream-json output recorded on 2026-10-01. They retain the
assistant tool-use events and matching user tool-result events. The project path was replaced with
`/recorded-project`; tool IDs and result fields are unchanged.

Both commands ran in a new project directory under the worktree's `.cat/work/temp`. The isolated
`CLAUDE_CONFIG_DIR` held a copy of the current authentication and settings; `TMPDIR` pointed to a new temporary
directory owned by the recording invocation. `--safe-mode` disabled customizations. The recording invocation
removed these directories after extracting the fixtures.

Source command for `claude-tool-calls.jsonl`:

```bash
printf '%s\n' 'Run exactly two separate Bash tool calls in order: true, then false. Then use Read to read sample.txt. Do not create or change any files. Reply done.' |
	claude -p --safe-mode --no-session-persistence --output-format stream-json --verbose \
		--tools Bash,Read --allowedTools 'Bash(true),Bash(false),Read'
```

The project contained `sample.txt` with `sample` followed by a newline. The successful Bash result reports
`stdout`, `stderr`, `interrupted`, `isImage`, and `noOutputExpected`, but no exit status field, so the parser records
0. The failed result's `tool_use_result` is the string `Error: Exit code 1`, so the parser records 1. The Read call
must have no command or status.

Source command for `claude-child-tool-calls.jsonl`:

```bash
printf '%s\n' 'Start one Agent whose task is to run exactly one Bash tool call: true, then reply child done. Wait for it. Do not run Bash yourself. Reply done.' |
	claude -p --safe-mode --no-session-persistence --output-format stream-json --verbose \
		--tools Bash,Agent --allowedTools 'Bash(true),Agent'
```

The root Agent call has a null `parent_tool_use_id`. The child's Bash call and its result identify that Agent call
as their parent. The parser must count the root Agent call and exclude the child's Bash call.

Source command for `claude-exit-statuses.jsonl`, recorded with Claude Code 2.1.287 and model `haiku` on 2026-10-01
in a new project directory under the worktree's `.cat/work/temp`, using the host's configuration:

```bash
printf '%s\n' 'Run exactly these Bash tool calls, one at a time, in this order, each as its own separate Bash call with no changes, even if one fails: (1) true (2) false (3) sh -c "exit 42" (4) grep zzz sample.txt (5) sh -c "sleep 1; exit 4" with run_in_background set to true (6) rm -f missing.txt. Do not create or change any files. Reply done.' |
	claude -p --safe-mode --no-session-persistence --output-format stream-json --verbose --model haiku \
		--tools Bash --allowedTools 'Bash(true)' 'Bash(false)' 'Bash(sh -c "exit 42")' 'Bash(grep zzz sample.txt)' 'Bash(sh -c "sleep 1; exit 4")'
```

The project contained `sample.txt` with `sample` followed by a newline. The fixture keeps the tool-use and tool-result
records. The project path was replaced with `/recorded-project`, including inside the background output path, and
the session's additional directory with `/recorded-additional-directory`. The parser must record:

- `true`: 0, from a result that is not an error and has no `returnCodeInterpretation`;
- `false` and `sh -c "exit 42"`: 1 and 42, from `tool_use_result` strings that start with `Error: Exit code N`;
- `grep zzz sample.txt`: 1. The command exits with status 1, but Claude reports it as a success with
	`returnCodeInterpretation` `No matches found`;
- the background command: no status, because its result carries `backgroundTaskId` and the status is not known yet;
- `rm -f missing.txt`: no status. Claude denied it without running it, and its error text has no exit code.

`claude-outside-calls.jsonl` contains four unchanged `assistant` records, lines 61, 334, 363 and 525, of the
repository's recording `docs/changelog/1.0/captures/languages/cpp/code/claude-cat.events.jsonl`, made with Claude Code
2.1.229 on 2026-08-14. They are, in order, two root Bash calls (`git status --short && git log --oneline -5` and a
`pkg-config … | grep …` pipeline), a `WebSearch` call and a `WebFetch` call of `https://www.boost.org/…`. The parser
must keep both commands as shell commands in the session's activity and record the outside sources `web search` and
`www.boost.org`. No repository recording contains an MCP tool call, so the MCP tests use synthetic `tool_use` blocks
named `mcp__<server>__<tool>`, the form that Claude Code gives to an MCP server's tools.
