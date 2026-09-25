## Scenario

Review this implementation plan before assigning its components. The product requires an archive of the editable
documents and the unchanged, confirmed read-only metadata file. The archive must be rejected if that metadata changes
after confirmation. Retrying delivery after a transient transport failure is an intended runtime behavior.

- `C2 Capture` runs before `C3 Confirmation`. It creates the initial document snapshot and later freezes the archive.
  Its freeze call receives a confirmed-roots reference. The roots record contains an editable-path list and a contract
  reference, but no metadata-baseline reference.
- `C3 Confirmation` consumes C2's initial snapshot and publishes the confirmed contract. The plan says a future
  version of that contract will contain the read-only metadata baseline reference. C3 is scheduled after C2.
- C2's freeze description says it preserves the confirmed metadata in the archive, but its inputs and current
  contract type provide no way to retrieve the baseline. Its unit test supplies a baseline reference separately.
- The runtime delivery state may return from `FAILED_TRANSPORT` to `READY` and retry the same archive. This loop
  does not create a new archive or change the confirmed baseline.

The components have not been implemented. Identify the smallest plan correction and the evidence needed before C2's
freeze behavior can be assigned to an implementer.

## Assertions

### backward-input-trace

The review traces C2 freeze's required metadata bytes backward through the roots record and contract to the baseline
producer. It notices that the separate unit-test argument is not a production input and that the current record has no
baseline reference.

### implementation-dependency-cycle

The review finds the implementation ordering cycle: C2 freeze needs the C3 baseline, while C3 is scheduled after the
whole C2 cluster for its initial snapshot. It requires a concrete, verified producer-to-consumer route or a split at a
real runnable boundary before releasing C2 freeze; it does not accept independently plausible component descriptions.

### runtime-loop-is-not-a-plan-cycle

The review does not prohibit the `FAILED_TRANSPORT` to `READY` runtime retry merely because it forms a state loop.
It keeps the archive and baseline identity fixed across that retry.
