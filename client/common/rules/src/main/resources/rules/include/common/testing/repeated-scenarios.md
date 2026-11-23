# Repeated Scenario Verification

## Design Goals

- Reuse verified setup across checks of different behaviors when each check still starts from its required state and
  produces its own acceptance evidence.
- Preserve clean-start and isolation guarantees where prior state or reused evidence could change the result.

## Guidance

Before repeating a scenario to check different behaviors, identify what each check must vary, which starting state it
requires, and the fresh observation that proves its result. Prepare the common state once through a supported path.
Reuse a live environment or a supported checkpoint when you can verify that its artifact, inputs, and state still
satisfy the next check. If the next check requires starting state that you have yet to observe in a live environment,
use a small check at the boundary that exposes that state.

Reset only the state that must change to satisfy the next check's starting conditions. For a lifecycle check, restart
the component whose transition the check asserts and keep other components running when they remain valid. Give checks
separate mutable state when they run independently or concurrently. If a determining input changed, reestablish and
verify the setup that depends on it. Use a fresh environment when the claim requires a clean start or prior state
cannot be brought back to verified starting conditions. Keep required clean-start and end-to-end acceptance checks.

When the system exposes events or durable results, use the event that establishes each check's required state to
trigger the next action. Verify the outcome in the record that owns it, using the operation's identity to distinguish
it from other work. Stop the check at the first safe boundary after its own evidence is recorded. Measure elapsed
time and expensive operations when claiming a speedup, with equivalent inputs and acceptance evidence in both runs.
