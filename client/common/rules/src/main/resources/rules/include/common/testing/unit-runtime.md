# Unit Test Runtime

## Design Goals

- Make each unit test do only the work needed to prove its claimed behavior, with execution ideally under one second.
  Accept a longer measured runtime when further speed would sacrifice the behavior being tested or require production
  changes that violate other quality or readability rules.
- When slow tests overlap or combine independently provable claims, preserve every distinct claim while reducing total
  work across the tests that prove them.

## Guidance

Aim for each unit test to finish in less than one second. Measure the individual test, including its setup and cleanup,
separately from compilation, discovery, and test-runner startup. The target is an ideal, not a cutoff.

When a test exceeds that target, state its behavioral claim and identify the operation and assertions that prove it.
Measure setup, repeated operations, persistence, waits, and external calls. Reduce work that the claim does not need;
use controlled inputs at the tested boundary instead of replaying predecessor workflows already verified elsewhere.
For each costly fixture, choose the smallest number of inputs, records, transitions, and external events that can make
the asserted behavior fail when it is wrong. Check each larger count or repeated step against a specific assertion of
that test, and remove repetitions that cannot change what it proves. Retain a policy threshold or boundary only when one
of the test's own assertions would fail if that threshold's value were wrong. A threshold that the exercised flow merely
passes through on the way to the claimed behavior does not require its production count: start from controlled state at
that threshold, or configure the smallest valid parameter, and leave the threshold's value to the test that claims it.
When a slow test makes several claims, identify each claim's smallest proving boundary, how many inputs must be present,
and how many must be read, validated, or processed. A batch-size claim may need many lightweight inputs but no
per-input processing; an outcome-processing claim may need only one processed outcome. Separate these claims when doing so
reduces the total setup, records, transitions, validation, and external calls across the affected tests. Keep a combined
check only when its own claim requires the interaction, and do not duplicate an expensive predecessor flow across new
tests merely to make each test shorter.
Before accepting a costly test fixture as necessary, inventory other tests that exercise the same decision or
interaction. For each claim, compare their input state, exercised boundary, and assertions; count existing coverage
only when a regression in that claim would make one of those tests fail. A larger fixture or traversal through the same
call path alone does not establish coverage. Compare the total suite work of reusing that coverage, adding a missing
assertion where the relevant setup already runs, and testing a distinct claim at a smaller real production boundary.
Retain the costly fixture only for a distinct claim those checks cannot prove; name the input count and processing that
claim requires. Preserve separate retry, recovery, and failure claims when deciding what can be removed.
When a focused claim is buried in a larger, expensive production flow, extract its real production responsibility into
an operation that accepts the inputs needed for that decision, then test that operation directly. If only visibility
prevents direct access, use a narrow test-access boundary instead of widening the public API. Keep a separate test of
the larger flow when its integration is itself a claimed behavior; do not replace that check with the focused one or
create a test-only alternate implementation.
If production code causes the delay, look for redundant processing or other straightforward inefficiencies and try to
correct them. Preserve required functionality, results, side effects, validation, recovery behavior, and test coverage.
When the behavior needs a fixed number of inputs, preserve that number without repeating unrelated producer work for
each input. Rerun the same behavioral check to verify its result and runtime.

Do not move, relabel, exclude, or disable a slow test, raise its timeout, or drop assertions to avoid fixing unnecessary
work. If further speed would sacrifice the behavior under test, and production code cannot be optimized further
without violating applicable quality or readability rules, accept the measured runtime. Record the work that requires
the time and why a faster check would lose coverage or harm code quality, naming the assertion of that test that needs
the work; a production threshold that the test passes through does not justify it. Keep the test in its required
verification gate.
