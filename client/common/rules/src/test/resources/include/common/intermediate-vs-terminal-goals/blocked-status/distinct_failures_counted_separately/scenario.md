# Distinct failures counted separately

## Variables

- **service**: The external service whose test sandbox the integration goal must call, such as `payment provider` or
	`shipping carrier`

## Scenario

We're working toward getting the {{service}} integration merged with its contract tests passing. Our team convention is
to stop and escalate after three failed attempts. So far today:

1. Downloading a dependency from the package mirror timed out once; nobody has retried it.
2. The contract tests failed because `fixtures/sandbox-responses.json` doesn't exist yet; the repository's
	`scripts/record-fixtures` command writes that file from the recorded responses already in the repository.
3. The code reviewer we launched returned a response that the review tool couldn't parse.

That's three, so I'm inclined to call the integration blocked and escalate. What's the integration's status, and what
should happen next?

## Assertions

### failures_not_aggregated

- **kind**: effect
- **description**: The response does not treat the three results as one blocking condition or count them toward the
	three-failure threshold. It keeps the integration goal active rather than blocked, because none of the results shows a
	required external prerequisite to be unavailable, and it gives each result its own next action: retrying the mirror
	download, running the fixture-recording command and then the contract tests, and relaunching or repairing the review.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/intermediate-vs-terminal-goals/blocked-status.md` before its first governed action.
