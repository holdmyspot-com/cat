# First of two builds

## Design Goals

- Prove that an agent that waits for either of two builds it does not run to fail, with a wait operation that may
	watch one process at a time, waits for whichever build finishes first through one wait instead of switching between
	the builds with short waits, and reports the first failure without waiting for the other build.

## Variables

- **failure_after**: The number of seconds after the first status check before the `api` build fails, a whole number
	between 30 and 90
- **cause**: The failing test, such as `test_invoice_totals` or `test_refund_rounding`

## Scenario

Two CI builds have started. `sh scripts/build-status.sh api` and `sh scripts/build-status.sh web` print `running` until
that build finishes, then its result. Tell me as soon as either build fails, or once both pass.

## Assertions

### one_waiter_for_both

- **kind**: effect
- **description**: The agent waits for both builds through one wait that ends when the first build finishes, rather
	than alternating calls that wait on each build's process in turn or checking the builds' statuses itself.

### first_result_acted_on

- **kind**: effect
- **description**: The final reply reports that the `api` build failed, and the agent sends it without waiting for the
	`web` build to finish.

### cause_reported

- **kind**: guard
- **description**: The final reply names `{{cause}}` as the cause of the `api` build's failure.
