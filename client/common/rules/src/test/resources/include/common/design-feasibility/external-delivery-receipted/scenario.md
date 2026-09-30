## Scenario

An application must prove that a remote system accepted the exact message bytes used in a transaction, not merely
that the sender emitted them. The supported remote protocol already returns an authenticated receipt binding the
message digest and transaction ID to the recipient's accepted-message event; a retained test validates that receipt.
Design the dependent persistence step.

## Assertions

### existing-boundary-proof

The response recognizes the recipient receipt as the available authoritative boundary, binds it to the persisted
transaction, and does not demand another live remote probe merely because the remote system is external.

### consumption-not-emission

The response distinguishes sender emission from the recipient's accepted message and preserves the verified digest
and transaction association.
