## Context

Use the [empty project fixture](../project/); the request supplies the planning inputs.

## Scenario

Create a short implementation plan for a local archive exporter. The caller supplies an immutable starting
manifest containing every input document and a source checkpoint. The already-verified ingestion API emits
events in strictly increasing global source order, including after reconnect, and deduplicates replay using a
durable event ID. The encoder consumes that ordered stream and writes the archive. The published contract, not a
mock, establishes those ingestion properties; a focused integration test is runnable. Scope is the new encoder
plus atomic publication and restart from its saved checkpoint. Preserve required outcome coverage and state what
checks the executor should run. Remain read-only: do not implement, run tests, or delegate.

## Assertions

### available-prerequisites

The plan accepts the explicitly supplied manifest/checkpoint and established global-order/replay guarantee. It does not
require a speculative discovery probe, duplicate an already-guaranteed ordering/deduplication component, or block all work.

### scoped-verification

The plan still assigns encoder, publication, and restart checks plus required project verification. A short coherent
sequence is sufficient; no new ledger, matrix document, or additional agents are required.

### planning-guidance-before-decisions

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### evidence-and-scope

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
