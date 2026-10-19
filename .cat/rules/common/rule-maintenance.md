---
paths:
  - ".cat/rules/**"
  - "client/**/rules/**"
  - "client/**/skills/**"
  - "client/distribution/**"
---
# CAT Rule Maintenance

## Design Goals

- Keep CAT's project rules, bundled rule sources, and harness-specific source directories distinct so every reader gets
	guidance that is available in its own repository and runtime context.
- Interpret a request to simplify a maintained rule, route, or workflow as a request to reduce the guidance its reader
	receives before each decision while preserving every decision when it becomes necessary, rather than as a request to
	shorten a report about that guidance.
- Ensure a simplified route gives each reader decision only the smallest authoritative guidance needed when that
	decision first arises, rather than preserving or discarding a whole loaded route as an indivisible unit.

## Guidance

CAT project rules belong under `.cat/rules/`; bundled rules belong under `client/common/rules` or the relevant
`client/<harness>/rules` source tree. Put harness-specific project rules under `.cat/rules/<harness>/` and
harness-specific bundled rules under `client/<harness>/rules/`. Keep common rule sources harness-neutral.

To preserve a short explanation of why one bundled-rule Design Goal exists without delivering that history to agents,
put a complete HTML comment immediately before the annotated Design Goal bullet. State the concrete prevented mistake
and the preserved invariant directly; do not put normative guidance only in the comment. Distribution assembles
source-only includes, then strips every HTML comment from staged rule Markdown before routing and installation. A
comment therefore documents source maintenance only and must not be the only place a packaged reader needs to make a
decision. Keep lengthy incident history and evolving evidence in source-only tests or records rather than comments.

When deciding whether guidance belongs in a bundled rule or CAT's project rules, lazy load
`rule-coverage/plugin-rule-placement.md`.

When a request names a maintained rule, route, or workflow as the thing to simplify, inspect the reader-visible
guidance that it loads before proposing a change. Map its repeated passages to their triggers, decisions, and evidence;
merge only passages that make the same decision under the same condition. A status update, explanation, or summary of
that guidance is a different artifact and does not satisfy the request. For example, “simplify the loaded route” means
reduce redundant instructions in the files that route loads, not shorten the explanation of those files. A request that
explicitly names a sentence, report, or message remains a request about that named prose. Before changing maintained
guidance, lazy load the canonical contract-design rule for its preservation and compaction procedure.

For each passage in a route being simplified, also record the first reader decision and observable state that require
it. Compare the reported route with a later branch whose condition is not yet known and a route that never reaches that
branch. Guidance needed only after the later condition is established should be delivered by a narrower, observable
trigger or companion route rather than added to the initial route. Keep guidance in the initial route only when the
reader must apply it to make the first decision safely or to determine that later condition. Do not remove or defer a
passage merely because the reported case did not use it: retain it when the initial decision needs it, and do not create
a companion that hides such an initial obligation. Verify the simplified route with one case that needs the deferred
decision and one that does not, so both receive exactly the guidance their next decision requires.

Do not conclude that a loaded route is already irreducible merely because all of its files were selected together.
For every first decision, identify the exact obligation it needs and its authoritative owner, then compare: retaining
the current route, merging duplicate obligations into one owner, and delivering a focused companion at the first later
decision that needs it. A candidate route is smaller only when its reader still has every required obligation before
making that decision, and no file supplies an obligation already available from the retained owner. Keep an initial
file when its unique obligation is needed to select a branch or make the first safe action; otherwise move that unique
obligation behind its observable trigger. If the mapping does not establish which file supplies an obligation, report
the route as not yet analyzed instead of treating its current size as necessary.
