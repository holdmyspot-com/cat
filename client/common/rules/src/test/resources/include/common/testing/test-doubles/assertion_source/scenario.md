# Assertion source

## Variables

- **first_price**: The price of the first line item, a whole number
- **second_price**: The price of the second line item, a whole number
- **total**: The sum of the two prices

## Scenario

Review each of the following proposed tests. No source edit is requested. Answer them in order, label each answer with
its request number, and say whether to accept the test as written; if not, say what should change.

**Request 1.** A session class reads a child process's output lines and ends the current turn either when the child reports that it is
idle with no background task listed, or when the child's output ends. A Java test replaces the child with an in-memory
fake process. The fake prints a recorded startup sequence that lists a background task and reports idle. The test then
calls the session's interrupt operation, which makes the fake print two lines: a task notification whose status is
`stopped`, and a line that still lists the background task. The test then calls the fake's `exit()` method and asserts
that the turn's captured output contains `"status":"stopped"`. The test is named "interrupt ends the turn while a
background task runs". No recording shows whether the real program exits after an interrupt.

**Request 2.** A Bash deployment script calls `curl` to query a health endpoint, parses the JSON `status` field, and must exit with
status 1 when the field is `error`. A Bats test puts a stub `curl` script first on `PATH` that prints
`{"status":"ok"}`, runs the deployment script, and asserts that its output contains `ok`.

**Request 3.** An order service computes an order's total from line items that it loads from a repository, and rejects an order with a
negative quantity. A test supplies an in-memory repository holding one order with two items priced {{first_price}} and {{second_price}}, asserts
that the service returns a total of {{total}}, and, with a second order holding a quantity of -1, asserts that the service
reports the negative quantity.

## Assertions

### relayed_value

- **kind**: effect
- **description**: For request 1: The agent rejects the assertion as written because `stopped` is a line the fake
  printed and the session only relayed, and the turn ended because the test told the fake to exit. It explains that a
  session that merely relays output and ends at end of output would pass, and asks for an assertion of a decision the
  session makes, or removal when another test already asserts that decision.

### unverified_reaction

- **kind**: effect
- **description**: For request 1: The agent identifies the fake's exit after the interrupt as an assumption about the
  real program. It requires that behavior to come from a recording or documentation of the real program, or from a test
  against the real program, and does not treat the fake-based test as evidence of it.

### analogous_stub

- **kind**: effect
- **description**: For request 2: The agent rejects the assertion because `ok` comes from the stub's output rather than
  from the script's decision. It asks for assertions on the script's exit status, using a stub response of `error` that
  must produce status 1 and one of `ok` that must not.

### allowed_double

- **kind**: guard
- **description**: For request 3: The agent accepts the test. The in-memory repository supplies input, and both
  assertions check outcomes the service computes: the sum and the rejection. The agent does not require a real
  repository or extra assertions.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/testing.md`,
  `rules/include/common/testing/test-doubles.md` before its first governed action.
