# Red gate per behavior

## Variables

- **method**: The name of the service's public resumption method, such as `resumeCancelled`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** A campaign-index recovery test failed because a committed cancellation could not be found. The developer added that
test before changing code, observed the expected failure, and made it pass. During the same bug fix, the developer
notices that an uncommitted cancellation intent may claim a key already owned by another campaign. No test yet
exercises that conflicting-owner case. The developer proposes adding the production check now and writing its test
afterward.

What should the developer do next?

**Request 2.** A service's `{{method}}()` method currently rejects every call at a temporary preflight. A test of its lower-level
index lookup first failed and then passed after a recovery fix. A reviewer finds that the body of `{{method}}()`
would reject a valid later campaign if an older campaign still owns the same key. The developer wants to change that
body and cite the passing lookup tests, although no test can currently reach it through `{{method}}()`.

How should the developer handle this correction and its evidence?

## Assertions

### new_outcome_needs_own_red_gate

- **kind**: effect
- **description**: For request 1: The response does not reuse the committed-cancellation test as a red receipt for the
	conflicting-owner behavior. It requires a focused test that exercises the conflict and fails for that behavior before
	the production check changes.

### preserve_prior_green

- **kind**: guard
- **description**: For request 1: The response retains the already-passing committed-cancellation test; it does not
	restart or discard that valid work.

### exercise_the_claimed_boundary

- **kind**: effect
- **description**: For request 2: The response does not treat lookup tests as proof of the public resumption behavior.
	It calls for a focused failing test reaching the affected resumption decision before the behavior change, using a
	practical testable path if needed.

### dormant_is_not_external

- **kind**: effect
- **description**: For request 2: The response does not classify the locally always-rejecting preflight as an external
	boundary exception or claim the resumption behavior is verified while it remains unreachable.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/tdd.md` before its
	first governed action.
