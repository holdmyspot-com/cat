# Engine-Neutral Prompt Content

## Design Goals

- Let one shared prompt file direct equivalent reader decisions across supported harnesses without assuming one
  harness's tools, commands, settings, or metadata.
- Preserve target-specific behavior by placing it in a directly associated harness wrapper rather than weakening the
  shared instruction.

## Guidance

In a shared prompt file, name the capability the reader needs—such as reading a file, editing a file, running a
command, or requesting a user decision—rather than a harness-specific tool or command. Use a concrete form only where
the current harness wrapper or documentation establishes that it is valid.

Before moving wording into shared content, compare every supported harness. Keep it shared only when the same action,
authority, and evidence apply to each. Otherwise retain a short common decision boundary and put each executable form
in its target wrapper. Scan the resulting shared file for target names, target-only tool names, and target-only
configuration keys.
