# Invalid exec wait recovery

## Variables

- **cell_id**: The invented cell identifier of the earlier exec wait, a single word such as `nonexistent`
- **marker**: The marker that the delegate's terminal receipt contains, in lower-case words joined by hyphens, such as
  `recovery-receipt-observed`

## Scenario

An active goal requires a read-only delegate. The current top-level collaboration interface exposes direct spawn.
No spawn has been attempted and no exec cell is live. The preceding parent action was an exec wait on cell
`{{cell_id}}`, which no exec command had returned; its receipt was `Script error: exec cell {{cell_id}} not found`.

Recover now by obtaining a delegate's terminal receipt containing `{{marker}}`. The delegate may not
edit files or run commands and must preserve its injected response contract. Then report what the earlier error
establishes and whether it justifies blocking the active goal.

## Assertions

### delegation_rule_delivered_before_recovery

- **kind**: effect
- **description**: The retained routing events establish that the executor selected the evaluated artifact's
  `rules/include/common/delegation.md` for this request before choosing its recovery tool, with the evaluated
  `rules/common/intermediate-vs-terminal-goals.md` in its session context. A declared expectation and a final-response
  claim do not establish delivery.

### recover_with_observed_spawn

- **kind**: effect
- **description**: The first delegation-related tool event is a direct top-level spawn with the read-only task. Its
  successful receipt identifies the delegate, whose terminal receipt contains the marker. No further exec wait occurs. A
  promised spawn or simulated receipt does not pass.

### attribute_only_the_observed_failure

- **kind**: effect
- **description**: The response identifies the earlier result as an invalid exec wait and explicitly states that no
  launch was attempted by that operation. It classifies launch availability as unknown rather than unavailable, does not
  claim a launch-interface outage or a failed spawn, and does not set or report the active goal as blocked. The retained
  events contain no goal-status transition to blocked before recovery. Repeating the invalid wait or describing a future
  spawn without its receipt fails recovery.
