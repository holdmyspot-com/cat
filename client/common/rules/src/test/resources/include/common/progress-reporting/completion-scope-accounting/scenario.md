## Scenario

The plan's readiness phase has five non-overlapping work packages. Input inventory, runner boundary, candidate state,
and scenario fixtures each have a passing focused verification receipt. Provider composition is unfinished, and the
readiness phase's aggregate acceptance gate has not run. The plan also has later implementation work, but no complete
non-overlapping inventory of that work is available. No terminal global wave has passed acceptance.

What percentage of the overall implementation plan is done?

## Assertions

### terminal-and-implementation-progress-remain-distinct

The response reports the terminal view separately from the readiness-prerequisite implementation view. It does not
describe the four verified work packages as terminally accepted global waves.

### verified-child-work-is-not-discarded

The response identifies four of five readiness work packages as verified progress and names provider composition plus
the aggregate acceptance gate as remaining work.

### no-terminal-only-overall-percentage

The response does not calculate an overall implementation percentage from the one terminal global-wave row. It either
states that an overall percentage is unknown because the global implementation inventory is incomplete, or calculates
one only after explicitly supplying a complete non-overlapping inventory at that scope.
