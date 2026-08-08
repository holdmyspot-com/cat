## Scenario

An agent is about to choose the next behavior slice in a campaign workflow. Candidate capture consumes a current
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

## Assertions

### producer-readiness-before-selection

The response maps several upcoming connected slices, traces each consumer input to an actual producer and focused
producer gate, and repairs or splits the assignment order before starting candidate capture, later call admission, or
document publication. Fabricated consumer fixtures or matching schemas do not establish producer readiness. It keeps
independent eligible work available.

### recovery-checks-before-edit

Before production-behavior edits to the durable workflow slices, the response assigns focused retry, restart,
missing-reference, and stale-evidence checks at the consuming boundary, with expected recovery or rejection and durable
evidence identified. It places testable behavior through a failing behavioral test before the relevant edit, and does
not defer these cases to a broad closing review.

### relevant-counterexample

The stateless formatter needs its own focused behavior check and prerequisites but no invented proof index, epoch, or
restart matrix. A general requirement to run four recovery tests for every slice fails this case.
