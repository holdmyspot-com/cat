---
paths:
  - "**/skills/**/*.md"
  - "**/agents/**/*.md"
  - "**/rules/**/*.md"
---
# Prompt File References

## Design Goals

- Keep prompt references actionable without duplicating mutable details from their authoritative sources.
- Make a routing entry select its target from an observable condition that exists before the target's outcome, rather
  than restating that outcome or the target's contents.

## Guidance

When a prompt file directs an agent to read an external file, name the file and why it is authoritative. Do not restate
the external file's current values, defaults, or other mutable content. Require the agent to read the referenced file
and use its values at execution time. Treat every path-shaped reference as external, including dotfiles, configuration,
source, data, and Markdown files.

When a prompt routes to another prompt file, state the observable request, artifact, or state that tells the reader when
to load it, then name the target. Keep the route limited to that trigger and target instruction. Do not use the target's
desired result as the trigger or summarize its guidance there; put outcomes and procedures in the target rule.

When a prompt references another prompt file, tool, or CLI, describe only what the caller needs to decide whether or
when to use it and to use it correctly: its observable capability and invocation outcome, plus required inputs, side
effects, limitations, returned keys, or alternatives when they change that decision. Write that a command verifies,
accepts, returns, or rejects a condition; do not state requirements for its internal implementation. Do not enumerate
its feature set, internal workflow, possible responses, validation, or recovery behavior; the referenced source or
invoked tool is authoritative for those details. Put implementation requirements in the target's development contract,
not caller guidance.

Before retaining a reference description, remove each sentence whose absence would not change tool or file selection,
invocation, required authority, supplied input, or consumption of a returned value. If a later decision needs a fact
that the reference does not expose, add that fact to the referenced contract instead of duplicating its implementation
or response list in the caller.
