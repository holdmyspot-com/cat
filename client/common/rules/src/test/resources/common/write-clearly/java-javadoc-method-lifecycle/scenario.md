## Scenario

In `BillingService.java`, create `quote()` for a caller to obtain the current quote. Update `applyDiscount()` so it
rejects a negative percentage. Move `formatReceipt()` from `BillingService` to `ReceiptFormatter`. Write or revise the
Javadoc for all three methods so it describes their current caller-visible contracts.

## Assertions

### write-clearly-applied

Each created, updated, or moved method's Javadoc states the caller's relevant input or state, the method's action, the observable result, and any caller-triggerable failure in plain terms. It does not substitute project shorthand, the former owner's name, or editing history for that contract.

### documented-created-method

The new Java method has Javadoc that states its caller-visible purpose, input effect, result, and applicable failure behavior.

### documented-updated-method

The changed Java method's Javadoc describes its current behavior rather than the behavior it had before the change.

### documented-moved-method

The method moved to the target Java class retains Javadoc that describes its responsibility in that class, without referring to the former class as its owner.
