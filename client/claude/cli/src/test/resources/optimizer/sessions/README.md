# Claude session recordings

Each JSONL resource contains every line of one turn's `artifacts/turn-N.events` file, in order. No lines or fields are
dropped, reordered or merged. The substitutions below are applied consistently throughout the streams, including
recorded text.

The recordings come from the installed plugin's optimizer campaigns, run with CAT 1.0 and Claude Code `2.1.304`; the
version is recorded in each stream's `system/init` record. Every session ran with
`claude --print --input-format stream-json --output-format stream-json --permission-mode acceptEdits --verbose
--include-partial-messages --no-session-persistence`, followed by the arguments in the table. The judge and triage
sessions also received `--add-dir` for the evaluated trial's `project` and `artifacts` directories.

The source paths in the table are relative to `/home/gili/cat/worktrees`.

| Resource | Role | Source | Date (UTC) | Arguments | Lines |
| --- | --- | --- | --- | --- | --- |
| [trial-turn-1.jsonl](trial-turn-1.jsonl) | trial | `e1-claude3/project/.cat/sessions/d3588df3-cf23-4ed8-9a67-f22ccea71aa8/optimizer-runs/claude/claude/mvg3attg-3cnrlozqqz/artifacts/turn-1.events` | 2026-10-20 | `--model haiku` | 71 |
| [two-turn-trial-turn-1.jsonl](two-turn-trial-turn-1.jsonl) | trial | `e1-claude3/project/.cat/sessions/d3588df3-cf23-4ed8-9a67-f22ccea71aa8/optimizer-runs/claude/claude/mvg39t5z-3cn6tj8d4g/artifacts/turn-1.events` | 2026-10-20 | `--model haiku` | 76 |
| [two-turn-trial-turn-2.jsonl](two-turn-trial-turn-2.jsonl) | trial | `e1-claude3/project/.cat/sessions/d3588df3-cf23-4ed8-9a67-f22ccea71aa8/optimizer-runs/claude/claude/mvg39t5z-3cn6tj8d4g/artifacts/turn-2.events` | 2026-10-20 | `--model haiku` | 16 |
| [judge-turn-1.jsonl](judge-turn-1.jsonl) | judge | `e1-claude3/project/.cat/sessions/d3588df3-cf23-4ed8-9a67-f22ccea71aa8/optimizer-runs/claude-held_out/claude/mvg9gzet-3g51dzkdtw/artifacts/turn-1.events` | 2026-10-20 | `--model opus --effort high` | 76 |
| [triage-turn-1.jsonl](triage-turn-1.jsonl) | triage | `e1-claude2/project/.cat/sessions/2c80699a-e7fa-4d29-824b-3989f9f45be5/optimizer-runs/claude/claude/mvg0gppf-3b379zib6t/artifacts/turn-1.events` | 2026-10-20 | `--model opus --effort high` | 61 |
| [prompt-author-turn-1.jsonl](prompt-author-turn-1.jsonl) | prompt author | `e1-claude2/project/.cat/sessions/2c80699a-e7fa-4d29-824b-3989f9f45be5/optimizer-runs/claude/claude/mvg0guv8-3b39rvka5p/artifacts/turn-1.events` | 2026-10-20 | `--model opus --effort high` | 211 |
| [contract-author-turn-1.jsonl](contract-author-turn-1.jsonl) | contract author | `e1-claude3/project/.cat/sessions/d3588df3-cf23-4ed8-9a67-f22ccea71aa8/optimizer-runs/claude/claude/mvg99bjx-3g0h7ailzp/artifacts/turn-1.events` | 2026-10-20 | `--model opus --effort high` | 592 |
| [interrupted-judge-turn-1.jsonl](interrupted-judge-turn-1.jsonl) | judge | `e1-claude1/project/.cat/sessions/b74db89c-1791-4027-8887-dd2e5f20ccf0/optimizer-runs/claude-held_out/claude/mvfvzvvn-38lqe8okht/artifacts/turn-1.events` | 2026-10-19 | `--model opus --effort high` | 57 |
| [e5-file-trial-turn-1.jsonl](e5-file-trial-turn-1.jsonl) | trial | E5 development guard pilot | not recorded | `--model haiku` | 382 |
| [e5-file-judge-turn-1.jsonl](e5-file-judge-turn-1.jsonl) | judge | E5 development guard pilot | not recorded | `--model opus --effort high` | 164 |
| [e5-text-trial-turn-1.jsonl](e5-text-trial-turn-1.jsonl) | trial | E5 development guard pilot | not recorded | `--model haiku` | 87 |
| [e5-text-judge-turn-1.jsonl](e5-text-judge-turn-1.jsonl) | judge | E5 development guard pilot | not recorded | `--model opus --effort high` | 132 |

The role of each session is shown by its reply: the judge replies with per-assertion verdicts, the triage session
with an `impact` decision, the prompt author with a report of needed edits and failure kinds, and the contract author
with the revised proposal it wrote. Every session completed except the interrupted judge's: CAT asked Claude to
interrupt that turn while it was thinking after a tool call, and the recording holds Claude's answer to that request, a
`control_response` line, followed by the `error_during_execution` result that ended the turn.

Substitutions made:

- The campaign's session directory, `<campaign>/project/.cat/sessions/<session-id>`, becomes `/recorded/project`; the
	campaign's project directory, `<campaign>/project`, becomes `/recorded/campaign`.
- Other `/home/gili/cat` prefixes become `/recorded/host`; remaining `/home/gili` prefixes become `/recorded/user`.
- Each substitution also applies to the path's dashed form, in which Claude names its project directory by replacing
	every `/` and `.` with `-`.
- The nested session IDs become `session-trial`, `session-two-turn-trial`, `session-judge`, `session-triage`,
	`session-prompt-author`, `session-contract-author` and `session-interrupted-judge`. The trial judged by the judge
	becomes `session-judged-trial`, the trial judged by the interrupted judge becomes `session-interrupted-judged-trial`,
	and the trial triaged by the triage session becomes `session-triaged-trial`.
- Each session's `session_id` becomes `trial-session`, `two-turn-trial-session`, `judge-session`, `triage-session`,
	`prompt-author-session`, `contract-author-session` or `interrupted-judge-session`. Every other UUID becomes a stable
	`identifier-N` value, numbered in order of first appearance across the resources in table order.

All other recorded fields and values remain unchanged.

The E5 recordings come from an unconfirmed development guard pilot on installed CAT `1.0+b08307c76ca2a` and Claude Code
`2.1.326`. The file-output pair's captured [answer.md](e5-answer.md) preserves the exact bytes checked by the live judge.
The E5 streams replace the original home paths and dashed path forms with stable `/recorded/...` or `-recorded-...`
forms. They replace UUIDs and
opaque event IDs with stable `recording-N` values in encounter order, preserving repeated references between events.
The event count and ordering remain unchanged.

`ClaudeSessionsContractTest` replays the saved trial and judge streams through the production Claude adapter. For the
file-output pair, the test checks that the recorded `Write` content matches `answer.md`, writes those captured bytes to
the trial effects directory, passes that file as read-only judge evidence, and confirms the judge leaves it unchanged.
It sends the recorded trial reply in the judge turn and checks the adapter forwards the evidence path to the harness.
The production `JudgePrompt.judgment` parses the recorded verdict. The file explanation passes while the separate
acknowledgement and chat-reply assertions fail. For the text-output pair, the test sends the recorded trial reply to
the judge and confirms the factual-answer assertion passes while the one-sentence assertion fails.

The replay exercises CAT's handling of saved Claude output. It does not run Claude or execute recorded file-tool calls.
The retained live receipt establishes that the native judge received the file and its verdict referred to its contents.
