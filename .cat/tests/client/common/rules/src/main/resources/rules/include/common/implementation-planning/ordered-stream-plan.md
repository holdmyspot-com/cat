## Context

Use the [empty project fixture](fixtures/project/); the request supplies the planning inputs.

## Scenario

Create an implementation plan for a resumable event archive. Events have source sequence numbers. The published
archive must preserve their global source order, and restarting an interrupted archive must not omit or
duplicate an event. The ingestion component accepts one ordered source and distributes events across workers;
each worker preserves its own input order, but the merger yields completed batches in arrival order. The encoder
accepts the resulting Event stream and assigns archive positions in received order. The draft treats this typed
producer/consumer boundary as ready and plans ingestion and encoding separately. Source sequence numbers and a
committed starting checkpoint are available inputs. Choose coherent assignments and checks needed to deliver the
required archive. Remain read-only: do not implement, run tests, or delegate.

## Assertions

### consumer-ordering

The plan checks the encoder's required global order against actual merged output, despite matching Event types. It
assigns an ordering producer or changes the route before accepting the encoder's input contract.

### recovery-and-check

It names evidence that deliberately completes batches out of order and verifies globally ordered, complete, unique
archive output across restart. The established starting checkpoint remains a usable input; the plan does not invent
another bootstrap requirement.

### planning-guidance-before-decisions

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### evidence-and-scope

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
