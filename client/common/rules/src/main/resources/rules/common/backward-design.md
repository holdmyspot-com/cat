---
depends-on:
  - ./intermediate-vs-terminal-goals.md
---
# Backward Design and Iterative Generalization

## Design Goals

- Start with the terminal observable outcome and its acceptance evidence, distinguish it from preparation, staging, and
  other intermediate state, derive the concrete conditions required to achieve it, and reverse those conditions into an
  executable sequence.
- Start with a concrete case or failure, compare meaningful similar and dissimilar cases—including a case that reaches
  the same outcome through a different mechanism when the proposed invariant names a mechanism and the nearest allowed
  case inside a proposed broader scope—retain the narrowest invariant that explains them without imposing unrelated
  obligations, and distinguish that invariant from the case-specific examples that validate it.
- Derive the smallest reliable change that satisfies the outcome conditions and established invariant while preserving
  required constraints.
- Apply this derivation to code-design decisions—including APIs, data representations, module boundaries, lifecycle,
  error handling, and concurrency—as well as prompt and workflow decisions; do not turn a mechanical edit that
  preserves an established design into an unnecessary redesign.

## Guidance

### Outcome and Invariant Derivation

Use backward design and iterative generalization together before recommending or designing a change; neither is complete
without the other. Start with the observable behavior and acceptance evidence, repeatedly derive the concrete directly
achievable preconditions, then reverse them into an executable sequence.

This applies to code design as well as prompts and workflows. Before choosing a public API, representation, component
boundary, lifecycle, failure behavior, or concurrency policy, derive the caller-observable result and acceptance
evidence, then compare the concrete design with a meaningful similar case and a relevant case that does not need that
decision. A local rename, formatting edit, or other mechanical change that preserves the existing design does not need
this design exercise.

For a multi-step workflow, derive the terminal goal and acceptance evidence before deriving its executable sequence. Use
the Intermediate versus Terminal Goals rule while executing that sequence.

Treat user-provided wording, examples, or proposed rules as inputs to evaluate, not permission to skip backward design
and iterative generalization. Apply the method before adopting, extending, or editing the proposal.

### Iterative Generalization and Scope

Treat Iterative Generalization as a design gate, not a label for an already chosen solution. Before selecting a rule,
scope, recommendation, or implementation, state the minimum concrete case; test the proposed invariant against the next
meaningful analogous case and a relevant dissimilar case; and generalize only the invariant shared by those cases.
Revise the invariant when either case exposes an unsupported assumption, then repeat with the next meaningful case
whenever it could change the scope. At each generalization, verify that the result covers the evidence, does not impose
unrelated obligations, and survives relevant boundary and dissimilar counterexamples. Do not select a candidate until no
examined meaningful case changes the invariant or its scope. Treat the original concrete case as a regression acceptance
check: the generalized guidance must still prescribe the decisions or checks needed to prevent that case. If the
required cases cannot be identified, investigate them before designing; do not replace the missing iteration with a
narrower example-based solution or a downstream guard. Select the most general abstraction that satisfies the goal,
preserves the acceptance evidence, and excludes relevant counterexamples without imposing unrelated obligations. When
the available cases or evidence do not prove that a broader, merged, or shorter statement preserves every existing
reader decision, retain the narrower existing statement and report the candidate improvement; do not infer that similar
wording permits a generalization.

When proposed wording broadens a predicate, category, quantifier, or prohibition beyond the property that caused the
original failure, test the nearest allowed case that the broader wording would also govern but that lacks that causal
property. If the wording changes the required action for that allowed case, narrow the wording to the causal property.
A dissimilar case outside the proposed scope cannot expose this overreach and does not satisfy this check.

Before adding a normative rule paragraph, make the generalization evidence explicit: identify the concrete case, one
meaningful analogous case, one relevant dissimilar case, and the invariant that survives all three. Then identify which
proposed wording is evidence-specific. Write the invariant as the obligation; retain case-specific wording only as a
clearly marked example when it materially helps the reader apply the invariant; otherwise remove it. Do not promote a
mechanism, artifact type, product name, or example's terminology into a general requirement unless the comparison proves
it is essential. Recheck the original case after this rewrite: the generalized obligation must still prescribe the
decision or check that would prevent it.

Do not use the initial artifact type or mechanism as the scope boundary unless the evidence proves it essential. An
analogous case that uses the same parser, workflow, artifact type, or other proposed mechanism can establish how that
mechanism behaves, but it cannot establish that the mechanism belongs in the invariant. When proposed wording names one,
compare a case that reaches the same required outcome through a different mechanism. For example, if a parser can erase
a required input distinction, also compare a mapper, storage layer, or configuration converter that can erase the same
distinction before the receiving operation uses it. Generalize to the shared information or decision relationship when
both cases require the same action; retain the mechanism only when the cross-mechanism case requires a different action
and the checkpoint states why. Then compare a dissimilar case where the relationship is absent or explicitly permitted
to differ, so the route does not load merely because the original mechanism appears. When a failure concerns a purpose,
claim, consumer, lifecycle, authority, or observable outcome, likewise compare one case in the same artifact type and
one case in a different artifact type that preserves that relationship. For example, when an explanation substitutes an
incidental caller or implementation detail for a component's own responsibility, compare a method Javadoc with a class
description, CLI help, or user-facing instruction that makes the same substitution. State whether the prevention governs
the relationship across those artifacts or a type-specific constraint, and name the concrete counterexample that proves
any narrower boundary. Do not stop after improving the reported format when the same reader decision can fail in another
format. When choosing a rule's scope or routing, make the next meaningful case an adjacent format, domain, or consumer
where the same outcome could occur. Decide explicitly whether the shared invariant belongs in a broader rule or must
remain narrow, and retain the reason as part of the design checkpoint.
