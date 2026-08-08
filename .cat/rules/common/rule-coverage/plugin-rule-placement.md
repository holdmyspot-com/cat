# Plugin Rule Placement Coverage

## Design Goals

- Put guidance in the rule layer where every intended reader can apply it without unavailable repository-specific
  context.

## Guidance

Before placing guidance in a bundled plugin rule, test it against an unrelated end-user codebase. It must remain
actionable without CAT's names, repository layout, workflow, commands, or implementation details. Generalize it when a
shared engineering decision exists; otherwise place CAT-specific guidance, examples, routing, and procedures in this
repository's `.cat/rules/` project rules. Do not make a plugin rule depend on a CAT project rule to become meaningful.

Generalize bundled rules across programming languages whenever the decision does not require one language's syntax,
standard library, or toolchain. When a language-specific rule remains necessary, keep it out of `rules/common/` and
place it at `rules/include/<language>/<rule>.md` in the bundled rule tree. A language-neutral direct rule must state the
observable condition that requires that companion and tell the reader when to lazy load it.
