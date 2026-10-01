## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

### Request 1

A campaign-index recovery test failed because a committed cancellation could not be found. The developer added that
test before changing code, observed the expected failure, and made it pass. During the same bug fix, the developer
notices that an uncommitted cancellation intent may claim a key already owned by another campaign. No test yet
exercises that conflicting-owner case. The developer proposes adding the production check now and writing its test
afterward.

What should the developer do next?

### Request 2

A service's `resumeCancelled()` method currently rejects every call at a temporary preflight. A test of its lower-level
index lookup first failed and then passed after a recovery fix. A reviewer finds that the body of `resumeCancelled()`
would reject a valid later campaign if an older campaign still owns the same key. The developer wants to change that
body and cite the passing lookup tests, although no test can currently reach it through `resumeCancelled()`.

How should the developer handle this correction and its evidence?

## Assertions

### new-outcome-needs-own-red-gate

For request 1: The response does not reuse the committed-cancellation test as a red receipt for the conflicting-owner
behavior. It requires a focused test that exercises the conflict and fails for that behavior before the production check
changes.

### preserve-prior-green

For request 1: The response retains the already-passing committed-cancellation test; it does not restart or discard that
valid work.

### exercise-the-claimed-boundary

For request 2: The response does not treat lookup tests as proof of the public resumption behavior. It calls for a
focused failing test reaching the affected resumption decision before the behavior change, using a practical testable
path if needed.

### dormant-is-not-external

For request 2: The response does not classify the locally always-rejecting preflight as an external boundary exception
or claim the resumption behavior is verified while it remains unreachable.
