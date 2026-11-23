# Codex session recordings

Each JSONL resource contains every line of its selected source file, in order. No lines or fields are dropped, reordered or merged. Substitutions are applied consistently throughout each stream, including recorded text. E1 source SHA-256 values identify native event-file bytes before substitutions.

The separate `../codex-hooks.jsonl` resource contains the two SessionStart notifications from turn 9 of the Codex 0.171.0 owner session in `wi146-integrated-runtime-Izw3OV/recovery-fixture`. The full source is under the retained fixture's `.cat/sessions/01a1fa89-2f6b-7a00-9ee4-d8de89a9e380/slots/d1/codex/s-1/artifacts/turn-9.events`. Its SHA-256 is `df0e3b3561b2b93d946864bd22124b95d7725bd706cf1bfedd050cd517b63351`. Turn 1 reports the CLI version for the same native thread. The fixture keeps the complete notification lines with the staging path, thread ID, and turn ID replaced. The tests use these two events to exercise hook-source attribution.

The refreshed trial, judge and prompt-author streams come from completed sessions in the installed Codex `1.0` plugin's E1 optimizer campaign. The source capture root is `/home/gili/cat/worktrees/v1.0/.cat/sessions/01a1e6ba-c063-78b0-9d33-7420983dae1e/slots/d1/codex/s-1/project/.cat/sessions/01a1ea74-1c11-7751-9ef0-e02d19116f71/campaign-records/20261117T170252Z-007bf4/slice-039-runs`. E1 source paths in the table are relative to this root; retained original paths are relative to the legacy root below unless absolute. Each selected native Codex CLI session completed with exit status 0 and exactly one completed turn. Selection is the lexicographically first completed eligible run for each role. The retained campaign contains 710 trial sessions, 935 judge sessions, 3 prompt-author sessions and 2 contract-author sessions; each session has one completed turn. Native `thread/started` events report codex-cli `0.160.0`.

| Resource | Source path | Capture date | codex-cli version | Lines | Raw source SHA-256 |
| --- | --- | --- | --- | ---: | --- |
| trial-turn-1.jsonl | codex-held_out/codex/mwkzckip-zzewzmxw8/artifacts/turn-1.events | 2026-11-17 | 0.160.0 | 62 | bbe719dec59c1f817d2733a7cec6d90608c2049a8d833eb9708b365d54933d69 |
| judge-turn-1.jsonl | codex-held_out/codex/mwky7sid-zbxrs07wo/artifacts/turn-1.events | 2026-11-17 | 0.160.0 | 228 | 065ee38c8370e894f9dbb82489b169bbda01da60b039a879fb6759f636712890 |
| author-turn-1.jsonl | codex/codex/mwkz3bub-zu3udgok3/artifacts/turn-1.events | 2026-11-17 | 0.160.0 | 549 | 3f18de1d90f0caca0d412b2ce102c08fa22e8f087b2d300d4f300108efce2f52 |
| deriver-turn-1.jsonl | mur87ghy-k9lf8ue0r/artifacts/turn-1.events | 2026-10-02 | 0.159.1 | 112 | source hash unavailable |
| deriver-turn-2.jsonl | mur87ghy-k9lf8ue0r/artifacts/turn-2.events | 2026-10-02 | 0.159.1 | 2385 | source hash unavailable |
| interrupt-turn-1.jsonl | /home/gili/cat-client/worktrees/e2-codex/receipts/interrupted-session/artifacts/turn-1.events | 2026-10-02 | 0.159.1+23 | 36 | source hash unavailable |

The contract-author stream available in this E1 capture is retained as a pending candidate rather than installed as `deriver-turn-1.jsonl`: it has one completed turn, while `deriver-turn-1.jsonl` and `deriver-turn-2.jsonl` form one recorded session. Replacing only its first turn would mix two sessions. Its native source is `/home/gili/cat/worktrees/v1.0/.cat/sessions/01a1e6ba-c063-78b0-9d33-7420983dae1e/slots/d1/codex/s-1/project/.cat/sessions/01a1ea74-1c11-7751-9ef0-e02d19116f71/campaign-records/20261117T170252Z-007bf4/slice-039-runs/codex/codex/mwkxxw7j-z67ff0ktk/artifacts/turn-1.events`; it completed with exit status 0, has 404 lines, reports codex-cli `0.160.0`, and its raw source SHA-256 is `db10a1cfece28db99bfd920511ffc5176e3d2f137b01dfa6c468b8662f236cb1`. The captured E1 sources contain no session with two completed contract-author turns and no interrupted session, so the existing deriver pair and interrupt recording remain together and unchanged.

The legacy optimizer source root for the existing role recordings is `/home/gili/cat-client/worktrees/v1.0/.cat/work/temp/e1d-codex-J7Kb2Opb/project/.cat/work/optimizer-runs/codex/codex/`. Those original source files are no longer available at that path. The table above retains their source paths, capture dates, versions and line counts; hashes for the prior fixture bytes are recorded below.

| Prior fixture resource | Lines before this refresh | Prior fixture SHA-256 |
| --- | ---: | --- |
| trial-turn-1.jsonl | 27 | 081c769644bbbbb3346db118ed7ccd453693f1bf2e0206372e006f2046efb481 |
| judge-turn-1.jsonl | 121 | 3f06d63d6d71f40d9df153438617b673cde34645b1fc39512902e53933563698 |
| author-turn-1.jsonl | 381 | 79419ad5d68e775e64d2c16c0c36ddc031d726840b3f5bfa9dc6e991f126b097 |
| deriver-turn-1.jsonl | 112 | a4844ada91e475d443a004564f73d6a86721927bd43c907067eb48d1f3cddeea |
| deriver-turn-2.jsonl | 2385 | a566723789d95e1075815bd58c027032bb6ddeae1b7f7fca90916764535b4779 |
| interrupt-turn-1.jsonl | 36 | 094e63a4f2bdd818ed1fc42f62fa39097dfa6aa3700f25db2ce4f0d3ee0c8118 |

The interrupt recording comes from the installed plugin's optimizer after ESC interrupts the host session mid-slice. The CAT driver sends `turn/interrupt` to its in-flight trial session; the recording contains response ID 5 and `turn/completed` with status `interrupted`. Its version above comes from `codex --version`; `thread/started` reports `0.159.1`.

[child-work.jsonl](child-work.jsonl) contains the complete 13-line source file [codex-tool-calls.jsonl](../codex-tool-calls.jsonl), captured on 2026-10-01 with codex-cli `0.159.1+23` from `codex --version`; its `thread/started` notification reports `0.159.1`. The capture command and provenance are in [codex-tool-calls.md](../codex-tool-calls.md).

Substitutions made in the refreshed E1 streams:

- The selected session's root thread and session identifiers become `trial-thread`, `judge-thread` or `author-thread`; its nested session ID becomes `session-trial`, `session-judge` or `session-author`.
- Other UUID identifiers become stable `identifier-N` values in first-observed order. Agent-message identifiers become `message-ROLE-N`; reasoning identifiers become `recorded-identifier-N`; command and file-change identifiers become `exec-identifier-N`. Repeated identifiers retain the same replacement.
- E1 command `processId` values become `process-N` in first-observed order, with repeated values retaining the same replacement. Distinct process identities remain distinct.
- Existing path substitutions apply throughout the streams: `/home/gili/cat-client` prefixes become `/recorded/host`, and remaining `/home/gili` prefixes become `/recorded/user`.

Existing recordings keep their established substitutions:

- The optimizer's enclosing `.cat/work` path becomes `/recorded/project`; the four nested session IDs become `session-trial`, `session-judge`, `session-author` and `session-deriver`.
- The interrupt recording's enclosing `.cat/work` path becomes `/recorded/project`; its nested session ID becomes `session-interrupt`, and its referenced trial's nested session ID becomes `session-interrupt-trial`.
- Root thread and session identifiers become role-specific stable names; turn and other UUID identifiers become `identifier-N` or `recorded-identifier-N`; agent-message item identifiers become `message-ROLE-N`; command item identifiers become `exec-identifier-N`; command process identifiers become `process`.
- The child capture's `/home/gili/cat-client/worktrees/v1.0-wi17a/.cat/work/temp/tool-events-recording` path becomes `/recorded/child`.
- Other `/home/gili/cat-client` prefixes become `/recorded/host`; remaining `/home/gili` prefixes become `/recorded/user`.
- Finding identifier `ac2fdab9e671697abf0df9d199023489cda88fdcd355b0f58477b17c15ddab86` becomes `finding-placeholder`.

Connection-local JSON-RPC response IDs retain their recorded values. All other recorded event fields and values remain unchanged apart from the substitutions above.

The interrupt check replays the complete interrupted-turn recording.

## Two-turn trial

The shared adapter contract replays the two completed turns of one replacement trial from the installed Codex
0.171.0 recovery rehearsal on CAT `1.0+bb4060268f76f`. The recovery receipt is `verified-trial-replay.json` under
session `01a1f722-8002-7742-8a1f-0d65e844b167/temp/wi146-q7-in-progress-tTWMC8/`. Both files come from the same
broker response, `responses/ae820858-1b64-4127-b60e-878c6b332c9e/results-copy/artifacts/`.

| Resource | Lines | Raw source SHA-256 |
| --- | ---: | --- |
| [two-turn-trial-turn-1.jsonl](two-turn-trial-turn-1.jsonl) | 28 | e2068f5ab850a23771406bb72fc4d9e0ab8049130952604ace3ae490ed28ba81 |
| [two-turn-trial-turn-2.jsonl](two-turn-trial-turn-2.jsonl) | 21 | a6dfce5b80b421193dee09ba4fab7bd9aba521780681f980a8c1f5f8c7f99e6f |

The recording replaces the trial's thread ID, other UUIDs and host path prefixes consistently across both turns.
The separate contract-author case continues to use the original deriver pair.

## Triage and approach ranking

Both streams retain every event from one completed Codex CLI 0.171.0 turn. The role is established by the
matching broker reply and its `impact` or `approaches` result. The replay uses each role's own stream.

| Resource | Retained fixture and child session | Lines | Raw source SHA-256 |
| --- | --- | ---: | --- |
| [triage-turn-1.jsonl](triage-turn-1.jsonl) | `t6-codex-VaHbsa`, `01a203f2-676d-7e22-9bc3-70250892db79` | 83 | `56377ef84a2ec8a00e83c023245c81e4197a3b9001ac3d0f6b73af42187f0dc8` |
| [approach-ranker-turn-1.jsonl](approach-ranker-turn-1.jsonl) | `e7-external-warning-WWdHnP`, `01a1ffe8-144f-7a03-977a-6342bdc997f5` | 559 | `76ffaea5af7fd6cf810bd8237bdce37f982521443637074a8ee3c5f70c86ba3e` |

Each source is the child session's `artifacts/turn-1.events` under the fixture's `optimizer-nested-sessions`.
Substitutions replace the fixture and home path prefixes, the root thread ID, and other UUIDs consistently.

## Two-turn optimizer roles

Four sessions captured on 2026-11-22 used Codex CLI 0.171.0 with the requested profile
`gpt-6-luna/low`. Each role acknowledged case number 308 on its first turn and returned exactly
`308` on its second turn. The capture's matching broker replies and native event-file hashes are
recorded in the goal session's temp directory under
`t3-role-capture-mxpI3v/served-codex/verified-roles.json`.

| Role | First turn (28 lines) | Second turn (13 lines) |
| --- | --- | --- |
| Judge | [two-turn-judge-turn-1.jsonl](two-turn-judge-turn-1.jsonl) | [two-turn-judge-turn-2.jsonl](two-turn-judge-turn-2.jsonl) |
| Triage | [two-turn-triage-turn-1.jsonl](two-turn-triage-turn-1.jsonl) | [two-turn-triage-turn-2.jsonl](two-turn-triage-turn-2.jsonl) |
| Approach ranker | [two-turn-approach-ranker-turn-1.jsonl](two-turn-approach-ranker-turn-1.jsonl) | [two-turn-approach-ranker-turn-2.jsonl](two-turn-approach-ranker-turn-2.jsonl) |
| Prompt author | [two-turn-prompt-author-turn-1.jsonl](two-turn-prompt-author-turn-1.jsonl) | [two-turn-prompt-author-turn-2.jsonl](two-turn-prompt-author-turn-2.jsonl) |

The replay checks the adapter's ordered replies and transcript for each pair. Both turns use
consistent replacements for the root thread and native UUIDs. The capture slot and user paths
become `/recorded/t3` and `/recorded/user`. The remote-control notification uses placeholders for the
workstation name and installation ID. The prompt author's two first-turn user-message events
preserve the case-number prompt and replace the appended rule text and source inventory with a
redaction marker. Each file retains the native event order, JSON structure, response IDs, and
completed agent replies.

## Interrupted trial

[interrupted-trial-turn-1.jsonl](interrupted-trial-turn-1.jsonl) contains all 17 lines of the first native turn
from the installed Codex 0.171.0 recovery fixture in session
`01a1f722-8002-7742-8a1f-0d65e844b167/temp/wi146-integrated-runtime-Izw3OV/`. The response directory
`responses/b1d4683f-6000-490a-bf89-7cc3691da01d/` holds the matching broker response, copied session manifest
and `results-copy/artifacts/turn-1.events`. The raw stream's SHA-256 is
`38efc874ef2d18f249c0a1b7f47e8617740eb49be1eb9a91adea4c9c84e1586d`.

The native stream ends with `turn/completed` and status `interrupted` after the interrupt response. The recording
replaces the thread ID, other UUIDs and host path prefixes throughout the stream. The adapter interrupt check
verifies the request's thread and turn identities against the recorded terminal event and retains the full transcript.

## E5 file and text evidence

These streams come from Codex CLI 0.171.0 on installed CAT `1.0+b30730961acbe`, campaign
`20261121T140529Z-86d2a1`. The retained `evidence/verified-codex-pilot.json` in session
`01a1f722-8002-7742-8a1f-0d65e844b167` under `temp/e5-codex-path-retry-a8lJvx/` ties the native pairs
to file pilot position 17 and chat pilot position 7. Both trial sessions used `gpt-6-luna` at low effort.

| Resource | Lines | Raw source SHA-256 |
| --- | ---: | --- |
| e5-file-trial-turn-1.jsonl | 141 | ffa08553be09e7e170ebeae1390e422851666cfdab2439ce87ee7097505af74b |
| e5-file-judge-turn-1.jsonl | 307 | 78eff866dfa5a207b05a723241f4436f06fb5acf66ecc779f94ef79582d23a34 |
| e5-text-trial-turn-1.jsonl | 30 | bcfe91c38fb794e139c6649c6a6809c80125b2feff5b1588afa43296e74fe61f |
| e5-text-judge-turn-1.jsonl | 203 | 4d8aa5e9daa534c34083041cd9b34565378bcde86b456f815102e77ad07de340 |

The four streams preserve event order and response IDs. The capture directory and remaining home prefixes are
replaced with `/recorded/e5` and `/recorded/user`. Root thread IDs use their resource role names. Other UUIDs
and command process IDs receive consistent replacements in first-observed order.

`e5-answer.md` contains the original staged file bytes. The file trial recorded a completed add, and the judge
received the same bytes in the trial's unified diff. The replay stages those bytes to model the recorded file
effect. The two replay tests feed the native events through the Codex adapter and parse the recorded verdicts
with the production judge parser.
