# Prompt versus CLI

## Design Goals

- Give code the workflow operations it can perform reliably under an explicit contract.
- Use models for the parts of a workflow that need judgment or original content.

## Guidance

Strongly consider replacing a model operation with code when its inputs and expected behavior can be specified without
model judgment. Evaluate existing and proposed operations, including calculation, validation, formatting, and applying
explicit decision rules. Prefer an existing interface or a small deterministic implementation that satisfies the same
contract.

When one step combines mechanical work with judgment or original writing, have code prepare the inputs and handle the
mechanical work. Use the model for the part that requires judgment or authorship. For delivery to the next program or
model, follow `./agent-handoffs.md`.
