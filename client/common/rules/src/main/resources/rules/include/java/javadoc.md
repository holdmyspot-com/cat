---
depends-on:
  - ../common/write-clearly.md
  - ../documentation/general.md
---

# Javadoc

## Design Goals

- Apply Java-specific Javadoc coverage, tags, placement, and syntax without duplicating the language-neutral
  documentation contract.
- Make boolean return contracts immediately recognizable as a condition the caller can test.

## Guidance

Write class, record, method, and constructor Javadoc using the multi-line form, even when the description is one
sentence. Do not put complete Javadoc on one line.

Document every class, record, explicitly declared constructor, and method, including non-public members. An `@Override`
method needs Javadoc only when it changes the inherited contract or adds important implementation details. When asked
only to document existing code, do not add a constructor, method, field, or other executable declaration merely to
create another documentation target.

Do not add Javadoc consisting only of `{@inheritDoc}`. An overriding method inherits its documentation by default.
Write new Javadoc only when it supplies caller-relevant information that the inherited contract does not already state.

Document every parameter with `@param` and every return value with `@return`. Place component `@param` descriptions on
a record declaration and repeat them on an explicit or compact canonical constructor. Put construction-contract tags
such as `@throws` only on that constructor, not on the record declaration.

For a method that returns a boolean, begin its summary description with “Indicates whether” and name the observable
condition that makes the result `true`. Do not describe a policy consequence that the method does not itself decide;
document that consequence at the caller or in the separately named operation that makes the decision.

An `@param` description must state its parameter's observable effect. When that effect selects a fixed caller-visible
value, boundary, policy, or ordering, name the exact selected behavior rather than a generic feature or action.

Use `<p>` alone on its own Javadoc line immediately after the preceding paragraph; do not insert a blank Javadoc line
before or after it, and do not use a closing `</p>` tag.
Import every class referenced by `{@link}` or `{@linkplain}` and use its simple name in the tag.

Describe semantic constraints in `@param`, such as a non-blank identifier. Document with `@throws` every deterministic,
externally observable exception that callers can trigger through documented inputs or required state. In `@throws`,
reference parameter names using `{@code parameterName}`. Use plural wording when one predicate governs two or more
parameters, and singular wording only when it governs one parameter or each parameter has a distinct predicate.
