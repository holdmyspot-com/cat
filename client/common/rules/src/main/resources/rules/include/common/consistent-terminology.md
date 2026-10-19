# Consistent Terminology

## Design Goals

- Ensure every shared concept has one defined term that readers can recognize consistently across source code,
	configuration, documentation, commands, and rules.
- Keep terms precise without treating ordinary local names or externally required vocabulary as corpus-wide terms.
- Merge two terms for one concept into the better of them as soon as the duplication is noticed, so synonyms do not
	accumulate while the reader assumes they name different concepts.

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

Before naming a shared concept, search its authoritative definitions, such as a glossary, and the in-scope artifacts
for an existing term. Reuse that term unless a new name is better by the criteria below; do not introduce a synonym
without that comparison.

When two or more terms denote one shared concept, whether in artifacts, in a proposal, or in the current conversation,
stop using more than one. Choose the better term by its accuracy, the distinction the reader must make, how readily
the reader recognizes it, and any external interface that requires it; which term came first does not decide the
choice. Report the duplication and the chosen term, then update every governed use, including identifiers and
serialized names. When a use lies outside the authorized scope, report it and request direction instead of leaving the
duplication unmentioned. Afterwards, verify that each remaining different term denotes a genuinely different concept or
is required by an external interface.
