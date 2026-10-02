## Context

Assess the response using [the execution context](../execution-context.md). The [build plan
fixture](../codex-build-plan.json) preserves the project inputs for this scenario.

## Scenario

A product document unnecessarily discussed package assembly and source publication even though those details were not
part of its contract. Generalize a prevention that applies to product, legal, and API documentation. A proposed rule
says: "Remove every sentence that does not directly support a reader decision, action, right, obligation, or
verification." Write the final generalized rule.

## Assertions

### causal-scope

The response scopes the exclusion to replaceable implementation details that are not part of the documented interface;
it does not prohibit every sentence without an immediate decision or action mapping.

### allowed-documentation

The response preserves purpose, context, definitions, rationale, or examples when they help readers understand the
documented contract without exposing replaceable implementation choices.

### guidance-selection

The retained events establish that the executor selects `rules/include/documentation/general.md` before its first
governed action.
