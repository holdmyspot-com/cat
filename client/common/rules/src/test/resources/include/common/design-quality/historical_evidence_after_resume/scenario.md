## Scenario

Review this implementation plan for a resumable package-import service. Accepted packages must not be imported twice after a crash, and an import may resume after the operator cancels an interruption.

The importer records a package-acceptance receipt before publishing the package. If interrupted, it records an acknowledged stop. Cancellation clears the active stop reference; the cancelled-origin resume event names the package and saved phase but omits the earlier stop and acceptance receipts. The plan says the final publication check will verify both receipts from the resume event. Its tests cover interruption followed directly by resumption and cancellation followed directly by resumption, but not interruption, acknowledged stop, cancellation, resumption, then publication after restart.

Separately, the same service parses a manifest by passing an immutable byte array directly from its reader to the parser in one synchronous call. No transition occurs between that read and parse.

Identify the smallest changes needed before the plan is handed to implementers. The service has not been implemented.

## Assertions

### durable_retrieval_after_composed_transitions

The review identifies that the final publication check cannot obtain the required historical receipts from the cancelled-origin resume event. It requires an authoritative surviving identity and retrieval path from persisted state for each required receipt across the complete interruption→acknowledgment→cancellation→resumption chain, or keeps that handoff unresolved. It does not treat prior publication or separate pairwise tests as proof.

### first_use_restart_proof

The review assigns a fault/restart check after the active reference is cleared but before the publication check's first use. The check must show that publication verifies the original accepted work without importing it twice, or fails closed when the historical evidence is missing or invalid.

### no_unneeded_historical_lookup

The review does not require a durable historical lookup or crash-recovery test for the direct immutable byte-array handoff to the synchronous parser. It distinguishes that case because the consumer receives and uses the exact value before any intervening lifecycle transition.

### guidance_selection

The retained events establish that the executor selects `rules/include/common/design-quality.md` before its first governed
action.
