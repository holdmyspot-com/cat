---
depends-on:
  - ../documentation.md
---

# Condition Descriptions

## Design Goals

- Make every documented condition immediately recognizable as one the caller can test.

## Guidance

When a field, property, method, function, type, or enum exists to report a condition, begin its summary description
with “Indicates whether”, not “Returns whether” or a bare “Whether”. Begin the description of a parameter or record
component that reports a condition with “indicates whether” or “`true` if”, not a bare “whether”. A condition is a value
that answers one yes-or-no question or picks one of two alternatives, whatever its type. Name the observable state that
makes the answer yes, or that selects each alternative. A callable that performs an action and also returns whether it
succeeded reports an outcome, not a condition: begin its summary with the action, and describe the outcome with its
result. Do not describe a policy consequence that the callable does not itself decide; document that consequence at
the caller or in the separately named operation that makes the decision.
