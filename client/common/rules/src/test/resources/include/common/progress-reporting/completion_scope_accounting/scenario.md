# Completion scope accounting

## Variables

| Name | Description |
|---|---|
| scope | The scope whose completion the user asks about, such as `overall implementation plan` |

## Scenario

What percentage of the {{scope}} is done?

## Assertions

### terminal_and_implementation_progress_remain_distinct

kind: effect

The response reports the terminal view separately from the readiness-prerequisite implementation view. It does not
describe the four verified work packages as terminally accepted global waves.

### verified_child_work_is_not_discarded

kind: effect

The response identifies four of five readiness work packages as verified progress and names provider composition plus
the aggregate acceptance gate as remaining work.

### no_terminal_only_overall_percentage

kind: effect

The response does not calculate an overall implementation percentage from the one terminal global-wave row. It either
states that an overall percentage is unknown because the global implementation inventory is incomplete, or calculates
one only after explicitly supplying a complete non-overlapping inventory at that scope.

### guidance_selection

kind: effect

The retained events establish that the executor selects `rules/include/common/progress-reporting.md` before its first
governed action.
