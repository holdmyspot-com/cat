# Consistent Terminology

## Design Goals

- Ensure every shared concept has one defined term that readers can recognize consistently across source code,
  configuration, documentation, commands, and rules.
- Keep terms precise without treating ordinary local names or externally required vocabulary as corpus-wide terms.

## Guidance

When defining, changing, or auditing a term used for a shared concept across source code, configuration,
documentation, commands, or rules, identify its authoritative definition and its in-scope artifacts. Use the same term
in each artifact unless an external interface requires different vocabulary; at that boundary, state the mapping and
keep the project-facing term consistent elsewhere.

Choose terminology from the distinction the reader must make, not from a broad label. For example, distinguish a named
value with one maintained definition (a **constant**) from a named configuration field supplied or changed outside its
consumer (a **property**) when that distinction affects the reader's decision. A local variable, parameter, or one-off
literal is not a shared concept merely because it has a name.

Use **prompt file** as the supertype for a reader-facing Markdown instruction artifact: a skill file, rule file, agent
instruction, command prompt, or a companion file that supplies its instruction content. Use the narrower term when the
reader must distinguish a skill's harness metadata, a rule's routing contract, an agent instruction's role, or a
command prompt's invocation. Do not use **prompt file** for source-code documentation, generated output, or a file that
only happens to contain a prompt fragment without supplying reader-facing instructions.

Before introducing a synonym or renaming an established term, inspect the authoritative definition and every in-scope
use. Make the change only when the existing term is inaccurate, ambiguous, or conflicts with an external required term.
Then update every governed use and verify that each remaining different term denotes a genuinely different concept or
is required by an external interface.
