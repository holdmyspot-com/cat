---
depends-on:
  - ./intermediate-vs-terminal-goals.md
agents: ["main"]
---
# User Request Scheduling

## Design Goals

- Acknowledge each user request promptly and apply related steering without losing unrelated requested work.
- Process unrelated user requests fairly in their arrival order after the active terminal goal completes.
- On session resumption, determine whether a goal is active from the goal-status interface rather than historical
  conversation context.
- Preserve the user's chosen constraints when translating a request into a tool or workflow invocation.
- Do not substitute the agent's plausible interpretation for a user request whose materially different meanings would
  require different work or produce different results.
- Reuse an established answer, investigation, or completed action when it still resolves the user's current request,
  instead of repeating work because the current agent context is incomplete.

## Guidance

When resuming a session, query the current thread's goal-status interface before deciding whether any user request
arrives during active work. Use that status for the decision. Historical conversation, summaries, and continuation
messages that say a goal is active or paused do not establish its current status.

When a user request arrives during active work, acknowledge receiving it immediately. Classify it using the Intermediate
versus Terminal Goals rule.

When the request relates to the current terminal goal, treat it as steering for the current work and apply it
immediately. An explicit request to stop, cancel, replace, or do work instead of the current goal is related steering
that changes the terminal goal; apply it immediately and retain or discard queued goals only as the user directs. When
the request is unrelated, append it to the queued goals after every request already queued; process queued goals
first-come-first-served after the current terminal goal completes.

Before researching, changing, or scheduling work, resolve a request's referent from the request and authoritative
conversation context. If two or more reasonable readings select materially different targets, scope, result, or next
action and that context does not choose one, ask a short clarification that names those alternatives and the decision
they change. Do not turn one plausible reading into a search query or a rule change merely because it is the most
recently mentioned term. Continue without asking only when every reasonable reading leads to the same safe, reversible
action and report which reading that action covers. For example, a request to update “the workflow” may refer to the
workflow being discussed or to the rule that governs it; ask which one when those changes would affect different
readers. A request that explicitly names a file and change does not need this clarification.

Before re-answering a question, repeating an investigation, or redoing an action whose subject appeared earlier in the
conversation, identify the prior result, the decision it answered, and the facts on which it depended. Reuse that result
when the current request asks for the same decision and those facts remain applicable. Repeat only when the user
requests a recheck, an input or external state that the result depended on could have changed, or newer evidence
contradicts the result. Establish the changed or disputed fact first, then repeat only the smallest action needed to
answer the current request. State what made the repeat necessary.

A context reset, compaction, handoff, or missing local recollection does not show that the earlier result is absent or
invalid. Inspect the available conversation record, summary, retained evidence, or authoritative current-state source
before treating the question as new. When that evidence cannot establish whether the earlier result applies, say so and
identify the smallest observation needed; do not silently repeat a broad investigation.

When translating a user request into a tool or workflow invocation, supply an optional limit, policy, or setting only
when the user explicitly provides it or the request identifies the project's documented default. Authorization to use a
capability does not authorize selecting its optional parameters. For example, create or resume a goal without a token
budget unless the user states one; pass a stated budget exactly.
