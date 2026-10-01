# Shared agent bodies

These harness-neutral bodies define stakeholder reviews, plan construction, plan review, and work-item implementation.
Claude and Codex wrappers supply only harness metadata, a capability tier, and an include reference to the matching body.

Stakeholder bodies review a declared change and report evidence-based findings or a clean approval. The plan-builder body
produces an implementation plan, while the plan-review body assesses a declared plan. These bodies are read-only.

The optimize body implements one delegated work item. It writes only within the delegation's write allowlist and
worktree, and its wrappers grant write tools for that purpose.
