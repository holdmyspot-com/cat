## Scenario

A service's `resumeCancelled()` method currently rejects every call at a temporary preflight. A test of its lower-level
index lookup first failed and then passed after a recovery fix. A reviewer finds that the body of `resumeCancelled()`
would reject a valid later campaign if an older campaign still owns the same key. The developer wants to change that
body and cite the passing lookup tests, although no test can currently reach it through `resumeCancelled()`.

How should the developer handle this correction and its evidence?

## Assertions

### exercise-the-claimed-boundary

The response does not treat lookup tests as proof of the public resumption behavior. It calls for a focused failing
test reaching the affected resumption decision before the behavior change, using a practical testable path if needed.

### dormant-is-not-external

The response does not classify the locally always-rejecting preflight as an external boundary exception or claim the
resumption behavior is verified while it remains unreachable.
