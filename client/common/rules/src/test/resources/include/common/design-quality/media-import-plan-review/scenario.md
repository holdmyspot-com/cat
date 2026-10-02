## Scenario

Review this implementation plan for a resumable media-import service and give the smallest changes needed before its
components are handed to implementers. The product requires imports to stay within each user's approved destination,
retain a completed import across restart, and show the user an accurate completion record.

- The plan relies on a vendor worker's `allowedDirectories` setting for write confinement. The team checked the
  configuration format with a stub worker, but has not observed a real vendor process enforce the setting.
- `ImportGateway` consumes `approvedDestinationRef` when it stages an import. `AccountPolicy` first publishes an
  approved destination after the user confirms the import. The plan names no earlier producer or provisional staging
  rule.
- `Worker.start()` returns a live handle. The handle's first `id()` call may fail, while its completion can still
  succeed. The plan discards the handle on that failure and starts a replacement after restart. Its test makes a later
  `id()` call fail only after an initial successful read.
- The plan retains a PASS from a review of revision 7 after revision 8 changed the gateway handoff. Neither the older
  snapshot nor a comparison of the reviewed input is retained.

The service has not been implemented. The team can run a bounded vendor-worker probe now, and it can implement and test
the gateway and worker adapter in separate clusters.

## Assertions

### authentic-capability-before-dependency

The review requires a bounded real vendor-worker check of the claimed write confinement before fixing dependent
interfaces. It does not treat the stub or configuration as behavior proof, and it does not require an end-to-end import
result before the service exists.

### produced-reference-at-use

The review exposes that `approvedDestinationRef` is unavailable when `ImportGateway` stages the import and requires a
producer, publication point, and consumer validation or a changed staging order before that handoff is accepted.

### first-observation-recovery

The review requires ownership of the live worker and a recoverable completion path when the *first* `id()` read fails.
It calls for a focused check that makes that first read fail and observes both successful completion and safe restart;
it does not treat the later-read test as proof of this path.

### review-provenance-and-clusters

The review does not reuse revision 7's PASS without a verifiable comparison of the inputs its finding depended on; it
requires a new affected-scope review when that comparison is unavailable. It releases dependent work after the
producer's focused result is current, then checks the combined handoff before accepting dependent behavior, without
claiming product acceptance from the plan review.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/design-quality.md` before its first governed
action.
