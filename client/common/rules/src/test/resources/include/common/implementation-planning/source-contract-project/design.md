# Document Export Design

This design was derived from `product-contract.md`.

## User journeys

1. An authorized user selects documents for export.
2. The user starts an export and waits for completion.
3. The user downloads the completed archive.

The design proposes a selection screen and an export worker. It marks the design complete but does not specify how the
service handles changed confirmed metadata, process restart, or the product contract's acceptance checks.
