# Completed change reported

## Variables

- **function**: A one-line Python function that returns the wrong result for an empty list, such as
	`def average(values): return sum(values) / len(values)`

## Scenario

This function should return `0` for an empty list instead of failing: {{function}}

Fix it, keeping its result for every non-empty list, and tell me when it's done.

## Assertions

### fixed_function

- **kind**: effect
- **description**: Returns a corrected function that returns `0` for an empty list and the same result as before for
	every non-empty list, and reports the fix as done.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/intermediate-vs-terminal-goals/blocked-status.md`
	for this request.
