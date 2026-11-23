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

[approach-ranker-turn-1.jsonl](approach-ranker-turn-1.jsonl) preserves all 177 events from Claude Code `2.1.326`
in the retained E5 fixture `e5-file-capture-JTfd0z`. The child session
`9c22a999-a3f7-47b9-b38b-3fa70e05539f` under `seed/.cat/sessions/1f8c1278-1c5a-40c3-b14d-76f962833896`
completed with a reply containing eleven approaches in a fenced JSON block. This fixture exercises delivery of
the recorded reply through the adapter. The raw `artifacts/turn-1.events` SHA-256 is
`cab5d39378d03c6a2e67dd7fa6d78eea85bf6aca52f572f864b419b568ae49f4`. Substitutions replace fixture and home paths,
their dashed forms, and UUIDs consistently.

## Contract-author correction

The retained `e7-claude2` contract-author session revised a proposal, then corrected two refused links in the same
Claude Code `2.1.304` conversation. Both turns have a successful native result. The adapter test requests the second
turn from its follow-up callback after observing the first reply, then checks both replies and the combined transcript.
The replay preserves the events for the file edits and validation commands. It does not execute the tools.

| Resource | Lines | Raw source SHA-256 |
| --- | ---: | --- |
| [contract-author-correction-turn-1.jsonl](contract-author-correction-turn-1.jsonl) | 1,242 | `19539d5aa82333709eeb0dee0282ce84d35a6b2f10186e350d6ad056f1152235` |
| [contract-author-correction-turn-2.jsonl](contract-author-correction-turn-2.jsonl) | 254 | `a81004514150f5de990becc36a09080c35bea41dfc1fb586d7cb751ce6e98c1b` |

The source is `e7-claude2/project/.cat/sessions/1028c466-4804-49cd-b059-2e797aded918/optimizer-runs/claude/claude/mvgunw5g-3smbcr65ar/artifacts/`.
Both files apply the same substitutions to project and home paths, their dashed forms, the artifact directory name,
and UUIDs.

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

## Two-turn trial

The shared contract check replays both successful turns of one Claude Code 2.1.326 trial from the R-I1
campaign `20261122T040953Z-953ff1`. The capture is in `temp/r-i1-skill-build-jcbJQl/` in session
`01a1f722-8002-7742-8a1f-0d65e844b167`. Nested session `c16894db-30f2-43a5-9b37-68cd635f4ab6` supplies both
streams. The two questions belong to one test case in the saved proposal.

| Resource | Lines | Raw source SHA-256 |
| --- | ---: | --- |
| [r-i1-trial-turn-1.jsonl](r-i1-trial-turn-1.jsonl) | 20 | bbb2ff28fde9be456890d4ba2791375e740e1988323af7c2b0f9af8e7e21e88f |
| [r-i1-trial-turn-2.jsonl](r-i1-trial-turn-2.jsonl) | 14 | 77fb67e5222b827f50fe0dcba6bafb3c58403428750aeafb682e0dba52a032a4 |

The fixture replaces the session ID, other UUIDs, and host path prefixes, including their dashed forms, across
both streams. The separate test of the earlier trial's exact responses retains its original pair.

## Interrupted session

[interrupted-session-turn-1.jsonl](interrupted-session-turn-1.jsonl) contains all 168 lines of the native Claude
Code 2.1.326 stream retained under `temp/e5-file-capture-JTfd0z/` in session
`01a1f722-8002-7742-8a1f-0d65e844b167`. The nested session directory `68c1040c-ccde-4acd-b822-a3c4f200a8fb`
contains the matching session manifest and `artifacts/turn-1.events`. The raw stream's SHA-256 is
`6e0d2ef076e3c905c876222f4ceffb3a152566305968e7fd21b6836650cf3189`.

The recorded stream has a successful interrupt control response before the `error_during_execution` result
with terminal reason `aborted_tools`. The replay verifies that the adapter sends exactly one interrupt request
and classifies the result as interrupted. The fixture replaces the session ID, other UUIDs, and host path prefixes,
including their dashed forms. Event order and fields are retained.

## E5 file and text evidence

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

## Two-turn optimizer roles

Four sessions captured on 2026-11-23 UTC used Claude Code 2.1.330 with the requested profile `haiku/low`; the
recorded model is `claude-haiku-5-5`. Each role acknowledged case number 308 on its first turn and returned exactly
`308` on its second turn. The capture's native event-file hashes are recorded in the goal session's temp directory
under `t3-role-capture-mxpI3v/served-claude/verified-roles.json`, in session
`01a1f722-8002-7742-8a1f-0d65e844b167`.

| Role | Resource | Lines | Raw source SHA-256 |
| --- | --- | ---: | --- |
| Judge | [two-turn-judge-turn-1.jsonl](two-turn-judge-turn-1.jsonl) | 16 | `be263128d8256fc49bfc90e05562f14cfd3e27e4f62f26050539eff1e1e726ba` |
| Judge | [two-turn-judge-turn-2.jsonl](two-turn-judge-turn-2.jsonl) | 12 | `21da6bc9ed04c730eea15540457f3633c7f9838e2896038481854c5970e578ea` |
| Triage | [two-turn-triage-turn-1.jsonl](two-turn-triage-turn-1.jsonl) | 27 | `dd2c921c45978a896e35e593b4cb210cc7d3f82bbe97314933fca51e14ba3a3c` |
| Triage | [two-turn-triage-turn-2.jsonl](two-turn-triage-turn-2.jsonl) | 12 | `262309d8c8e426789503aadec04e2fb263f58a8b6f2fe85ede7215b8220c35f8` |
| Approach ranker | [two-turn-approach-ranker-turn-1.jsonl](two-turn-approach-ranker-turn-1.jsonl) | 23 | `a0848bd1bfb63e2b20fec77c2e9b52e442ac5fb54f77628468bddc46bc856b7a` |
| Approach ranker | [two-turn-approach-ranker-turn-2.jsonl](two-turn-approach-ranker-turn-2.jsonl) | 12 | `fb7cb0a3de91ac0a5709c7085fdfa508d7ead84214c7afc517247798a762b62f` |
| Prompt author | [two-turn-prompt-author-turn-1.jsonl](two-turn-prompt-author-turn-1.jsonl) | 23 | `b50a8a4252993037062e3d115604016730a2446d7cdfc82fed2ec8fdc81e59e2` |
| Prompt author | [two-turn-prompt-author-turn-2.jsonl](two-turn-prompt-author-turn-2.jsonl) | 12 | `4f9b4a38b65183d67e80c55e3e4e35aed385550d207bbf6a0b9ad44513d55e62` |

The replay checks the adapter's ordered replies and transcript for each pair. Each file keeps every line of its
native stream in order, with its JSON structure unchanged. The recordings replace values consistently across both turns
of a session:

- The capture slot path under the project directory becomes `/recorded/t3`, and the user home and user paths become
  `/recorded/user`. The same capture path in Claude's dash-encoded project folder name becomes `-recorded-t3`.
- The session ID becomes `two-turn-<role>-session`, and every other UUID becomes `identifier-<n>` in order of first
  appearance.
- The remote-control socket path becomes `/recorded/socket`.
