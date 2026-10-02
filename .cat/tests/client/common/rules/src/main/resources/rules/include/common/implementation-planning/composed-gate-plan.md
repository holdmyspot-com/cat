## Context

Use the [empty project fixture](fixtures/project/); the request supplies the planning inputs.

## Scenario

Create an implementation plan for an interruptible document-analysis service. It must stop admitting new model calls
when the user interrupts, request cancellation through an existing runner, and resume completed work without repetition.
The runner exposes launch, cancel, and retained-result lookup; those capabilities can be probed independently today.
A draft gate P requires an ESC action through the finished service to prove all three outcomes before any implementation.
The draft schedules the service's durable call admission in component A after P and the ESC invocation adapter in
component B after A. Neither service component exists. An independent report formatter has a complete contract and
runnable focused tests. Produce verifiable assignments and gates for the required service. Remain read-only: do not
implement, execute probes or tests, or delegate.

## Assertions

### distinguish-capability-and-composition

The plan separates the available runner-capability probe from the service-owned admission/ESC/recovery result. It does
not accept runner cancellation alone as proof of the finished service's behavior.

### first-runnable-gate

It permits the minimal A/B implementation needed for the composed check, schedules that check at the first runnable
boundary, and keeps dependent acceptance conditional until it passes. It permits independent formatter work. It does
not require a future consumer to exist before assigning the component that produces it.

### planning-guidance-before-decisions

The retained events establish a successful read of `rules/include/common/implementation-planning.md` before the first
project inspection or decision about behavior coverage, assignments, dependencies, or checks. A failed lookup,
substituted skill, or later read does not satisfy that boundary. Assess the plan's decisions separately from guidance
selection.

### evidence-and-scope

Reject unsupported numerical durations, universal speed claims, or substituted scope. Accept alternative decompositions
whose stated dependencies and checks satisfy the request; do not grade by literal phrases. Keep guidance selection or
delivery unverified when the retained events do not establish it.
