# Codex session recordings

Each JSONL resource contains every line of its source file, in order. No lines or fields are dropped, reordered or
merged. The substitutions below are applied consistently throughout the streams, including recorded text.

The role recordings come from the installed plugin's optimizer run under
`/home/gili/cat-client/worktrees/v1.0/.cat/work/temp/e1d-codex-J7Kb2Opb/project/.cat/work/optimizer-runs/codex/codex/`.
Their codex-cli version is recorded in `thread/started`.
The role source paths in the table are relative to that optimizer run directory; the interrupt source path is absolute.

| Resource | Source | Date | codex-cli version | Lines |
| --- | --- | --- | --- | --- |
| [trial-turn-1.jsonl](trial-turn-1.jsonl) | `mur8qp4f-kkjz93jbz/artifacts/turn-1.events` | 2026-10-02 | `0.159.1` | 27 |
| [judge-turn-1.jsonl](judge-turn-1.jsonl) | `mur8wxxu-ko47vfaq7/artifacts/turn-1.events` | 2026-10-02 | `0.159.1` | 121 |
| [author-turn-1.jsonl](author-turn-1.jsonl) | `mur8ipgs-kg04fxykc/artifacts/turn-1.events` | 2026-10-02 | `0.159.1` | 381 |
| [deriver-turn-1.jsonl](deriver-turn-1.jsonl) | `mur87ghy-k9lf8ue0r/artifacts/turn-1.events` | 2026-10-02 | `0.159.1` | 112 |
| [deriver-turn-2.jsonl](deriver-turn-2.jsonl) | `mur87ghy-k9lf8ue0r/artifacts/turn-2.events` | 2026-10-02 | `0.159.1` | 2385 |
| [interrupt-turn-1.jsonl](interrupt-turn-1.jsonl) | `/home/gili/cat-client/worktrees/e2-codex/receipts/interrupted-session/artifacts/turn-1.events` | 2026-10-02 | `0.159.1+23` | 36 |

The interrupt recording comes from the installed plugin's optimizer after ESC interrupts the host session mid-slice.
The CAT driver sends `turn/interrupt` to its in-flight trial session; the recording contains response ID 5 and
`turn/completed` with status `interrupted`. Its version above comes from `codex --version`; `thread/started` reports
`0.159.1`.

[child-work.jsonl](child-work.jsonl) contains the complete 13-line source file
[codex-tool-calls.jsonl](../codex-tool-calls.jsonl), captured on 2026-10-01 with codex-cli `0.159.1+23` from
`codex --version`; its `thread/started` notification reports `0.159.1`. The capture command and provenance are in
[codex-tool-calls.md](../codex-tool-calls.md).

Substitutions made:

- The optimizer run's enclosing `.cat/work` path becomes `/recorded/project`; the four run identifiers become
  `run-trial`, `run-judge`, `run-author` and `run-deriver`.
- The interrupt recording's enclosing `.cat/work` path becomes `/recorded/project`; its run identifier becomes
  `run-interrupt`, and its referenced trial run identifier becomes `run-interrupt-trial`.
- The child capture's `/home/gili/cat-client/worktrees/v1.0-wi17a/.cat/work/temp/tool-events-recording` path becomes
  `/recorded/child`.
- Other `/home/gili/cat-client` prefixes become `/recorded/host`; remaining `/home/gili` prefixes become
  `/recorded/user`.
- Root thread and session identifiers become `trial-thread`, `judge-thread`, `author-thread`, `deriver-thread` or
  `root-thread`; the interrupt thread and session identifiers become `interrupt-thread`, and the recorded child thread
  becomes `child-thread`.
- Turn and other UUID identifiers become stable `identifier-N` or `recorded-identifier-N` values. Agent-message item
  identifiers become `message-ROLE-N`; command item identifiers become `exec-identifier-N`, and command
  process identifiers become `process`.
- Finding identifier `ac2fdab9e671697abf0df9d199023489cda88fdcd355b0f58477b17c15ddab86` becomes
  `finding-placeholder`.

Connection-local JSON-RPC response IDs retain their recorded values: 4 for the first role turn and interrupt turn,
5 for the second deriver turn and the interrupt request. All other recorded fields and values remain unchanged.

The interrupt check replays the complete interrupted-turn recording.
