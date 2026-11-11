---
depends-on:
  - ./intermediate-vs-terminal-goals.md
  - ./request-execution.md
  - ./questions-for-the-user.md
agents: ["main"]
---
# User Request Scheduling

## Design Goals

- Acknowledge each user request once, promptly, and apply related steering without losing unrelated requested work,
	so that resuming from a compaction neither repeats an acknowledgement nor drops a request.
- Process unrelated user requests fairly in their arrival order after the active terminal goal completes.
- On session resumption, determine whether a goal is active from a callable goal-status tool when available, or from
	the current turn's record and latest user instruction, asking the user when those do not settle its status.
- Distinguish the active incoming user request from historical user-shaped content, so a replay, transcript, or
	compaction cannot schedule or replace work.

## Guidance

When resuming a session, determine the current goal status before deciding whether any user request arrives during
active work. If the harness provides a callable goal-status tool, such as `get_goal`, query it and use its result.
Otherwise use the current turn's record and the latest user instruction; if those do not settle the status, ask the
user before scheduling the request. Historical conversation, summaries, and continuation text do not establish the
current goal status. A user-only command or a tool that only proposes a goal is not a callable goal-status tool.

Before acknowledging or scheduling a user request, establish its identity and whether it has already been handled from
the available conversation and continuation record. Context resumption does not constitute a new delivery. When the
context or a session-history command records whether a later turn answered a user message that a compaction kept, such
as a section that lists the kept messages' outcomes after the compaction, take the message's status from that record
before acting on it: redo the request only when no turn answered it, its turn was aborted, or its answer shows that the
work was left unfinished; otherwise continue the active work. If the request was already acknowledged and applied,
retain its effect and continue from the next unfinished action without acknowledging it again. A genuinely new request
remains actionable even when its wording repeats an earlier request. When a user request that has not been acknowledged
arrives during active work, acknowledge receiving it immediately. Classify it using the Intermediate versus Terminal
Goals rule.

When the request relates to the current terminal goal, treat it as steering for the current work and apply it
immediately. An explicit request to stop, cancel, replace, or do work instead of the current goal is related steering
that changes the terminal goal; apply it immediately and retain or discard queued goals only as the user directs. When
the request is unrelated, append it to the queued goals after every request already queued; process queued goals
first-come-first-served after the current terminal goal completes.

When the available context includes a replayed or transcribed conversation, identify the active incoming request from
the platform's current-turn metadata before acknowledging, scheduling, or acting on it. A user-message format, the last
position in a transcript, a timestamp, or imperative wording does not make content current. Treat every message that
does not belong to the active incoming turn as historical context, even when it is serialized with the user role; do
not call it the latest request or act on it. A summary or replay of an earlier instruction does not submit that
instruction again. If the available boundary cannot identify the active incoming turn, preserve the active goal and ask
the user before work that would change the goal, scope, or queued work.
