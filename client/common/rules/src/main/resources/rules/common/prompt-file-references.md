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
- Let an agent find each file that a prompt file names without knowing the directory that the prompt file came from.

## Guidance

When a prompt file directs an agent to read an external file, name the file and why it is authoritative. Do not restate
the external file's current values, defaults, or other mutable content. Require the agent to read the referenced file
and use its values at execution time. Treat every path-shaped reference as external, including dotfiles, configuration,
source, data, and Markdown files.

When a prompt file names another file for an agent to read, name it from a root that the agent's context states:
`${CAT_PLUGIN_ROOT}` for a file of the CAT plugin, `${CAT_PROJECT_ROOT}` for a file of the project, `${CAT_USER_RULES}`
for a user rule, or, in a skill, the skill's own directory for a file of that skill. Never name it relative to the
prompt file's own directory, such as `../include/x.md` or a bare `x.md`. A `depends-on` entry and a `cat:include`
directive stay relative, because CAT resolves them, not the agent.

When a prompt routes to another prompt file, state the observable request, artifact, or state that tells the reader when
to load it, then name the target. Keep the route limited to that trigger and target instruction, plus any read order or
condition that the reader needs to decide whether or when to load it, such as "then `b.md` in that order" or "before
dispatching the run". Do not use the target's desired result as the trigger, and do not state what the target does or
achieves: a route has no purpose clause, such as "to preserve …" or "for its … procedure", after the target. Put
outcomes and procedures in the target rule.

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
