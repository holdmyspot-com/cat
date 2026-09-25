## Scenario

Design a reliable prompt-source receipt for an optimizer using an installed agent runner. A startup hook sends a file
path and optional context into the harness; a tool transcript shows that the hook ran. The product must record the
exact source bytes the host actually used, including a late-loaded source. No retained host event or receipt has yet
been checked for consumed content. Independent statistical and Git work does not depend on this loading boundary.

## Assertions

### evidence-before-dependent-design

The response identifies that an emitted path or context is not evidence that the host consumed particular bytes. It
checks an existing authoritative consumption receipt if one is available or proposes the smallest real supported-host
probe before committing the dependent loading schema and implementation; it does not invent a receipt from a mock.

### bounded-gap

If the host does not expose consumption evidence, the response states the precise missing fact/authority, preserves
the requested guarantee as unresolved, and allows independent work to continue instead of silently narrowing scope.
