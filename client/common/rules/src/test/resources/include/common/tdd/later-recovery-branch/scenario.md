## Scenario

A campaign-index recovery test failed because a committed cancellation could not be found. The developer added that
test before changing code, observed the expected failure, and made it pass. During the same bug fix, the developer
notices that an uncommitted cancellation intent may claim a key already owned by another campaign. No test yet
exercises that conflicting-owner case. The developer proposes adding the production check now and writing its test
afterward.

What should the developer do next?

## Assertions

### new-outcome-needs-own-red-gate

The response does not reuse the committed-cancellation test as a red receipt for the conflicting-owner behavior. It
requires a focused test that exercises the conflict and fails for that behavior before the production check changes.

### preserve-prior-green

The response retains the already-passing committed-cancellation test; it does not restart or discard that valid work.
