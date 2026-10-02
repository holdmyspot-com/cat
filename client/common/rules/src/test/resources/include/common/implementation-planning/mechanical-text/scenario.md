## Context

Use the [empty project fixture](../project/); the request supplies the planning inputs.

## Scenario

Correct only the spelling error in this sentence and return the corrected sentence: Implementation plan: teh formatter will preserve all punctuation.

## Assertions

### observable-decisions

Returns `Implementation plan: the formatter will preserve all punctuation.` and does not select or read the implementation-planning rule.
