# Producer before consumer

## Variables

| Name | Description |
|---|---|
| retry_state | The runtime delivery state after a transient transport failure, in upper case with underscores, such as `FAILED_TRANSPORT` |

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** An agent is about to choose the next behavior slice in a campaign workflow. Candidate capture consumes a current
measurement baseline, but the component assigned to produce that baseline is scheduled later. A later call admission
consumes a source observation from a second invocation, and no assigned component produces it. Local happy-path tests
for both consumers could be written with fabricated fixtures. The prior slice is closed, and the next cluster has not
been selected.

The same workflow stores proof and an index separately, permits cold retries after process restart, and advances an
epoch while retaining earlier evidence. The existing plan lists only normal-result checks. Explain what must be settled
before selecting and editing the next slice, and which checks belong to that slice.

Then consider an analogous document pipeline: publication consumes a signed approval from a later module, and a saved
document can be retried after a restart with its index absent or approval stale. Finally, consider an independent
stateless text formatter with no durable references or retry semantics. Explain how the same decision procedure applies
to each.

**Request 2.** Review this implementation plan before assigning its components. The product requires an archive of the editable
documents and the unchanged, confirmed read-only metadata file. The archive must be rejected if that metadata changes
after confirmation. Retrying delivery after a transient transport failure is an intended runtime behavior.

- `C2 Capture` runs before `C3 Confirmation`. It creates the initial document snapshot and later freezes the archive.
  Its freeze call receives a confirmed-roots reference. The roots record contains an editable-path list and a contract
  reference, but no metadata-baseline reference.
- `C3 Confirmation` consumes C2's initial snapshot and publishes the confirmed contract. The plan says a future
  version of that contract will contain the read-only metadata baseline reference. C3 is scheduled after C2.
- C2's freeze description says it preserves the confirmed metadata in the archive, but its inputs and current
  contract type provide no way to retrieve the baseline. Its unit test supplies a baseline reference separately.
- The runtime delivery state may return from `{{retry_state}}` to `READY` and retry the same archive. This loop
  does not create a new archive or change the confirmed baseline.

The components have not been implemented. Identify the smallest plan correction and the evidence needed before C2's
freeze behavior can be assigned to an implementer.

## Assertions

### producer_readiness_before_selection

kind: effect

For request 1: The response maps several upcoming connected slices, traces each consumer input to an actual producer and
focused producer gate, and repairs or splits the assignment order before starting candidate capture, later call
admission, or document publication. Fabricated consumer fixtures or matching schemas do not establish producer
readiness. It keeps independent eligible work available.

### recovery_checks_before_edit

kind: effect

For request 1: Before production-behavior edits to the durable workflow slices, the response assigns focused retry,
restart, missing-reference, and stale-evidence checks at the consuming boundary, with expected recovery or rejection and
durable evidence identified. It places testable behavior through a failing behavioral test before the relevant edit, and
does not defer these cases to a broad closing review.

### relevant_counterexample

kind: guard

For request 1: The stateless formatter needs its own focused behavior check and prerequisites but no invented proof
index, epoch, or restart matrix. A general requirement to run four recovery tests for every slice fails this case.

### backward_input_trace

kind: effect

For request 2: The review traces C2 freeze's required metadata bytes backward through the roots record and contract to
the baseline producer. It notices that the separate unit-test argument is not a production input and that the current
record has no baseline reference.

### implementation_dependency_cycle

kind: effect

For request 2: The review finds the implementation ordering cycle: C2 freeze needs the C3 baseline, while C3 is
scheduled after the whole C2 cluster for its initial snapshot. It requires a concrete, verified producer-to-consumer
route or a split at a real runnable boundary before releasing C2 freeze; it does not accept independently plausible
component descriptions.

### runtime_loop_is_not_a_plan_cycle

kind: guard

For request 2: The review does not prohibit the `{{retry_state}}` to `READY` runtime retry merely because it forms a
state loop. It keeps the archive and baseline identity fixed across that retry.

### guidance_selection

kind: effect

The retained events establish that the executor selects `rules/include/common/design-quality.md` before its first governed
action.
