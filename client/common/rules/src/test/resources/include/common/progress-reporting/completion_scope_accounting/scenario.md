# Completion scope accounting

## Variables

- **scope**: The scope whose completion the user asks about, such as `overall implementation plan`
- **verified_packages**: Table rows of the plan's W1 readiness work packages whose status is `verified`, one row per
	package
- **open_packages**: Table rows of the plan's W1 readiness work packages whose status is `open`, one row per package,
	such as provider composition and the aggregate acceptance gate

## Scenario

What percentage of the {{scope}} is done? The plan is `docs/implementation-plan.md`.

## Assertions

### terminal_and_implementation_progress_remain_distinct

- **kind**: effect
- **description**: The response reports the terminal view separately from the readiness-prerequisite implementation
	view. It does not describe the verified readiness work packages as terminally accepted global waves.

### verified_child_work_is_not_discarded

- **kind**: effect
- **description**: The response counts the readiness work packages in {{verified_packages}} as verified progress out of
	all the readiness work packages, and names each package in {{open_packages}} as remaining work.

### no_terminal_only_overall_percentage

- **kind**: effect
- **description**: The response does not calculate an overall implementation percentage from the one terminal
	global-wave row, W0. It either states that an overall percentage is unknown because the global implementation
	inventory is incomplete, or calculates one only after explicitly supplying a complete non-overlapping inventory at
	that scope.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/progress-reporting.md`
	before its first governed action.
