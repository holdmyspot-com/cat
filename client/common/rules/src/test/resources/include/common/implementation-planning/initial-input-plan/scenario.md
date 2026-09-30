## Scenario

Create an implementation plan for this document optimizer. The user selects an existing document set and
confirms its acceptance tests. The system measures the initial documents, proposes edits in the active worktree,
measures revisions, and returns a stronger revision or a plateau with retained evidence. It must also handle an
initial set that already passes without requiring a pointless edit. A draft component design has Preparation
capturing the selected document bytes and confirmed tests; Revision Workspace producing a revision ID and
immutable snapshot after a successful authoring call; and Evaluation accepting that revision ID to load the
snapshot and run tests. Every evaluation, including the initial measurement, is described as consuming the
Revision Workspace result. The draft puts Evaluation before Revision Workspace and lets the first authoring call
depend on initial evaluation results. There are no other persisted revisions on a first invocation. Turn the
product requirements and draft into coherent, verifiable assignments, deciding readiness and order. Preserve the
product outcomes. Remain read-only: do not implement, execute tests, or delegate.

## Assertions

### initial-producer

The plan supplies a real immutable initial snapshot/revision from selected source bytes before first evaluation. It does
not require a successful authoring call or a fixture-only argument to create that initial input.

### executable-order

The plan splits responsibilities or changes the handoff so baseline capture precedes evaluation, which precedes
candidate authoring. It retains initial measurement, an already-passing initial set, revisions, and plateau evidence,
and names a first-invocation acceptance check. A component list or a future audit alone is insufficient.
