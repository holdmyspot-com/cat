## Scenario

Review this design before it becomes the input to an implementation plan. The authoritative product contract requires a
document-export service to let an authorized user export selected documents, reject an export if its confirmed read-only
metadata changes, preserve an export completed before a process restart, and expose a completion result. Its acceptance
gates require an authorized export, a changed-metadata rejection, and a fault-and-restart check after completion but
before the caller first observes it.

The design contains three user journeys: selecting documents, starting an export, and downloading a completed archive.
It describes a selection screen and an export worker, then marks the design complete. It does not mention the confirmed
metadata, restart recovery, or the corresponding gates. The product contract remains available to the reviewer. Give
the smallest correction needed before implementation planning; do not implement the service.

## Assertions

### source-obligations-survive-design

The review compares the design with the authoritative contract and identifies the missing changed-metadata rejection,
durable completed result, and failure interval before the caller first observes completion. It does not approve the
design merely because its three journeys are coherent.

### acceptance-and-authority

The review requires design decisions and observable evidence for each missing obligation before dependent assignments
are made. It keeps any unsupported mechanism or genuinely missing product decision unresolved with a named owner,
without guessing an answer or treating a proposed test as passing evidence.

### no-format-requirement

The review permits the contract and corrected design to express obligations through shared rules, use cases, a mapping,
or another authoritative form. It does not require another journey document or duplicate the already supplied product
contract.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/design-quality.md` before its first governed
action.
