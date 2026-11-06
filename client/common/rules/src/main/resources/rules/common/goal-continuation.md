---
depends-on:
  - ./intermediate-vs-terminal-goals.md
agents: ["main"]
---
# Intermediate versus Terminal Goals: Continuing an Active Goal

## Design Goals

- Keep working toward an active goal instead of stopping or yielding because of turn length, context, task size, or a
	dirty worktree.

## Guidance

With an active goal, do not stop because of turn length, context, task size, or a dirty worktree. Before yielding, take
the next in-scope read, check, or edit. A dirty worktree is state to preserve, not a blocker.
