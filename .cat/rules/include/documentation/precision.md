# Documentation Precision

## Design Goals

- Provide operationally precise documentation that lets each audience complete its supported workflow without mixing in
	another audience's instructions.
- Organize instructional flows so readers can follow shared decisions, alternatives, and their rejoining next steps
	without inferring the structure.

## Guidance

For code that cleans up, repairs, migrates, compensates for a platform lifecycle behavior, or coordinates artifacts,
documentation must identify the specific artifact or state affected, the actor or operation that acts on it, and the
observable result. When describing order, precedence, replacement, or another relationship, state the participating
items, the sequence or condition, and why the relationship is allowed or required. Do not rely on broad or ambiguous
labels such as "files", "data", "configuration", "ordered", or "replace" when the precise context is known. When an
operation can discard or replace existing state, document the condition that triggers it, the affected state, and the
resulting state. Do not describe only the successful end state when that would conceal data loss or another
consequential side effect. When an operation is permitted only by a declaration, policy, or precondition, also state the
observable outcome when the condition is not met; do not leave readers to infer whether the operation is rejected,
ignored, or resolved by a default.

When an abstract description does not make a behavior, constraint, or decision operationally clear, use the shortest
concrete example that demonstrates it. Identify the representative input or state, the observable result without the
behavior, and the resulting form or invariant. Do not rely only on labels such as "safe", "normalized", or "path-aware"
when an example can state the consequence directly.

When command documentation or help supports distinct invocation modes, defaults, or argument forms with different
semantics, show one concrete invocation for each mode and state its effect. Do not rely only on compact optional or
alternative syntax when readers would have to infer which value selects which behavior or what omitting an argument
does.

Keep a related instruction, its condition or default, its consequence, and its concrete example adjacent in one local
block when readers need them together to act correctly. Do not separate them with unrelated prose or a heading. This
does not require unrelated information to share a block; group only the facts needed to understand and use the same
instruction.

For an instructional page with a multi-step or alternative flow, present shared decisions in the order readers execute
them. Make an option group and its option children clear in the heading hierarchy, and keep option-specific instructions
inside the matching child section. After alternatives, add an explicit shared next-step section when readers rejoin the
same flow. Use numbered stages only when their order matters. Apply this structure to any workflow with choices, not
only installation or harness selection; do not add headings to simple prose that has no meaningful choice or sequence.

Public end-user documentation must present one consistent supported flow. Put developer-only, local-build, test, or
repository-checkout alternatives in contributor or developer documentation and label them as such, unless ordinary end
users are intended to use them. Before publishing an audience-facing workflow, identify its audience and verify
lifecycle symmetry: that audience can find every relevant entry, transition or update, and exit or recovery action
without being redirected to a different audience's workflow. Do not invent stages the workflow does not have. For an
installation workflow, the relevant actions are install, upgrade, and uninstall. If a page documents more than one
audience, separate and label the flows before their commands.
