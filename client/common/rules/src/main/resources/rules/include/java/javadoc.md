---
depends-on:
  - ../common/write-clearly.md
  - ../documentation/interface-contracts.md
  - ../documentation/declared-values.md
---

# Javadoc

## Design Goals

- Apply Java-specific Javadoc coverage, tags, placement, and syntax without duplicating the language-neutral
	documentation contract.

## Guidance

Write class, record, method, and constructor Javadoc using the multi-line form, even when the description is one
sentence. Do not put complete Javadoc on one line.

Document every class, record, explicitly declared constructor, and method, including non-public members. An `@Override`
method needs Javadoc only when it changes the inherited contract or adds important implementation details.

Do not add Javadoc consisting only of `{@inheritDoc}`. An overriding method inherits its documentation by default.
Write new Javadoc only when it supplies caller-relevant information that the inherited contract does not already state.

Document every parameter with `@param` and every return value with `@return`. Place component `@param` descriptions on
a record declaration and repeat them on an explicit or compact canonical constructor. Put construction-contract tags
such as `@throws` only on that constructor, not on the record declaration.

When the results that `@return` must describe do not fit in a short `@return`, summarize them there and describe each
result and its condition in the method's main description.

Use `<p>` alone on its own Javadoc line immediately after the preceding paragraph; do not insert a blank Javadoc line
before or after it, and do not use a closing `</p>` tag.
Import every class referenced by `{@link}` or `{@linkplain}` and use its simple name in the tag.

Use `@throws` for each documented exception, and reference parameter names in it using `{@code parameterName}`.
