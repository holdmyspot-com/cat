---
depends-on:
  - ../documentation.md
---

# Source Comments

## Design Goals

<!--
An explanation for the next Guidance paragraph was placed after the last Design Goal, where it appeared to explain
that goal instead. This made the comment's subject unclear, so the goal requires placing explanations with their
actual subject.
-->
- Let readers identify which code or prose an explanatory comment describes without guessing whether it applies to
  the preceding or following item.
- Make a comment that justifies a Design Goal state the known situation that prompted the goal, and leave an unknown
  origin unclaimed rather than invent one.
- Let a maintainer learn from the comment beside a deliberate divergence from the normal approach why the divergence
  is required and what would fail without it, so that it is neither removed nor copied incorrectly.

## Guidance

Place a standalone explanatory comment immediately above the code or prose it describes, including any annotations
belonging to a declaration. Do not separate it from that subject with an unrelated heading, declaration, or paragraph.
Identify its subject before moving it: a syntax-controlled directive or a clear same-line comment stays where its
syntax and meaning require. After editing, check that a reader encountering the comment can identify what it explains.
When an explanatory comment justifies a Design Goal, place it immediately above that goal and state the concrete
situation that prompted the addition and how the goal addresses it. Do not substitute a generic example, a restatement
of the goal, or a fabricated incident for the known reason it was added. If that reason is unavailable, do not invent
one; say what is known and leave the origin unclaimed.

When maintained code deliberately differs from its normal peer, shared design, or platform-neutral approach because of
an external boundary, document the decision beside that implementation. State the normal approach, the observed
external constraint or failure, the selected alternative, and the guarantee it preserves. Do not add this rationale for
ordinary local choices; use it for a durable, non-obvious divergence that a future maintainer could otherwise remove or
copy incorrectly. Make the explanation self-contained: name the triggering input or state, the external component's
observable behavior, the operation that then fails and its result, and how the workaround changes that sequence. A
reader must be able to determine why the workaround is required and what would fail without it without reproducing the
external behavior or consulting an incident record. Where the failure depends on two paths, identifiers, values, or
states that should agree but do not, name both the expected and actual form and the resulting incompatibility. For
example, explain that a tool writes a spill file under one path but asks its consumer to read a differently formed path,
so the read targets no file; do not reduce that causal chain to “a spill-path issue” or “a sandbox problem.” State the
workaround's replacement artifact or operation and why it avoids the incompatible external behavior.
