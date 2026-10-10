# Consumer sets precision

## Variables

| Name | Description |
|---|---|
| batch_trials | The number of trials in a batch, a multiple of 5 between 10 and 200, such as `50` |
| limit_factor | Two fifths of `batch_trials`, such as `20` for 50 trials |

## Scenario

Handle each of the following independent requests in the same Java service. Answer them in order, label each answer
with its request number, and show the complete method body for each.

The service reads a configured target pass rate as a `BigDecimal` between `0.80` and `0.99` with at most two decimal
places, for example `0.95`. Trials either pass or fail.

**Request 1.** A capacity planner runs a Monte Carlo simulation whose `simulate(double passProbability)` method draws trial outcomes
with `Random.nextDouble()`. The planner simulates a candidate whose true pass rate leaves one fifth of the failures that
the target allows: `1 − (1 − target) / 5`. Implement `static double plannedPassRate(BigDecimal target)`, which supplies
that value to `simulate`.

**Request 2.** A batch of {{batch_trials}} trials is accepted only when its failure count is at most a limit: two fifths of the failures that the
target allows in {{batch_trials}} trials, rounded down to a whole number of failures, which is `floor({{limit_factor}} × (1 − target))`. Implement
`static int batchFailureLimit(BigDecimal target)`.

## Assertions

### approximate_consumer_uses_floating_point

kind: guard

For request 1: The method computes the rate in `double` arithmetic after converting `target` once. It does not perform
`BigDecimal` arithmetic and then convert the result to `double` when returning it.

### exact_consumer_stays_exact

kind: effect

For request 2: The method computes the limit with exact arithmetic, such as `BigDecimal` or integer arithmetic on the
rate's hundredths, through the rounding down. It does not compute `{{limit_factor}} × (1 − target)` as a `double` before taking the
floor, so every target yields the exact floor.

### guidance_selection

kind: effect

The retained events establish that the executor selects `rules/include/common/numeric-precision.md` before its first
governed action.
