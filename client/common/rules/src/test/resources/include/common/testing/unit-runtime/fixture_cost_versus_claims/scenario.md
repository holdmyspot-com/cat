# Fixture cost versus claims

## Variables

- **failure_count**: The number of consecutive failures after which the production configuration opens the breaker, a
	whole number between 10 and 200

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** A unit test takes 19 seconds. It creates three 50-outcome batches, publishes a category decision, assigns the next
trial, retries publication, and repairs a missing assignment index. A direct test already checks category selection
from aggregate counts in less than a second. A separate, larger campaign test already executes the same publication
and assignment after those three batches; its assertions would fail if the selected category or assignment reference
were wrong. That larger test does not check retry or missing-index repair. Decide what the 19-second test must still do
to preserve its distinct claims while reducing total test work. State the evidence needed before calling the 150
retained outcomes necessary.

**Request 2.** A unit test decodes 500 records to assert which page comes next. A separate 700-record test already crosses that page
boundary and asserts the next-page token that the smaller test checks. A direct pagination-policy test can decide the
next page from a lightweight count and token. The 500-record test also checks recovery from a missing page index, which
the other tests do not exercise. Decide how to retain coverage with the least total decoding and setup work.

**Request 3.** A unit test provides 500 items to verify that the public API rejects item 501. Another 700-item test passes through
that count while exercising a different output, but none of its assertions would fail if the API accepted item 501.
No direct policy test establishes the public rejection. Decide whether the 500-item test can be removed or reduced to
one item, and what would justify retaining its runtime.

**Request 4.** A unit test takes 2.7 seconds. It asserts that a certification driver moves a campaign through readiness, a formal
attempt, certification and delivery in that order. To get there it simulates 193 trials: at the default minimum pass
rate of 0.95, readiness needs 100 passing trials and the formal attempt stops after 93 more. Direct tests already check
the readiness limit and the formal attempt's stopping boundary. The driver accepts its minimum pass rate and a starting
campaign state from a builder. The test's Javadoc says that it takes over a second because the driver runs every trial
that readiness and a formal attempt need. Decide whether the test needs those 193 trials and what its Javadoc should say
if it still takes over a second.

**Request 5.** A unit test takes 1.8 seconds. It asserts that a circuit breaker schedules a half-open probe once its open window
expires. It reaches the open state by sending {{failure_count}} failing requests, because the production configuration opens the
breaker after {{failure_count}} consecutive failures. A direct test already checks that the breaker opens at exactly that count. The
breaker's failure count is configurable, and its state can be set directly through its package-private constructor.
Decide what the probe test needs.

## Assertions

### existing_coverage_before_fixture_claim

- **kind**: effect
- **description**: For request 1: The agent maps each claim to an existing failure-capable assertion before deciding
	whether the 150-outcome fixture is needed. It does not infer that the separate 19-second test is necessary merely
	because the policy uses three batches. The aggregate selector check proves the selection decision; the larger campaign
	check proves the stated publication and assignment interaction.

### unique_recovery_claim

- **kind**: effect
- **description**: For request 1: The agent keeps retry and missing-index recovery covered. It looks for a focused
	production responsibility or narrow test-access boundary that lets those behaviors be tested with fewer records,
	rather than dropping the assertions or repeating the full predecessor campaign. It retains expensive integration only
	for a distinct claim that such a focused check and the existing campaign check cannot prove.

### shared_claim

- **kind**: effect
- **description**: For request 2: The agent recognizes that the existing 700-record assertion and direct policy test
	already cover the next-page choice. It does not keep 500 decoded records solely to repeat that choice.

### distinct_recovery

- **kind**: effect
- **description**: For request 2: The agent preserves missing-index recovery in a focused check at the smallest real
	production boundary capable of proving it, or identifies a distinct interaction that still requires an integrated
	fixture. It compares total suite work rather than moving all decoding into a second large test.

### boundary_remains_proved

- **kind**: effect
- **description**: For request 3: The agent keeps a failure-capable check of the exact public limit using the required
	input count. It does not treat path traversal in the 700-item test as proof, or replace the 500-item public-boundary
	claim with a one-item processing test.

### necessary_work_only

- **kind**: effect
- **description**: For request 3: The agent still removes unrelated per-item work where the boundary does not require
	it. It accepts a longer measured runtime only if further reduction would lose the rejection claim or violate quality
	and readability constraints.

### pass_through_threshold

- **kind**: effect
- **description**: For request 4: The agent identifies that the test's claim is the order of transitions, and that no
	assertion of that test would fail if the readiness count or the formal attempt's stopping boundary changed. It starts
	the driver from builder state at each threshold, or with the smallest valid minimum pass rate, instead of simulating
	193 trials, and leaves the threshold values to their direct tests.

### justification_names_own_claim

- **kind**: effect
- **description**: For request 4: The agent rejects the existing Javadoc reason, because a production threshold that the
	test passes through does not justify its runtime. If the test still takes over a second, the recorded reason names the
	assertion of that test that needs the remaining work.

### analogous_pass_through

- **kind**: effect
- **description**: For request 5: The agent reaches the open state directly or configures a failure count of one, rather
	than sending {{failure_count}} failing requests. It does not treat the production count of {{failure_count}} as
	required, because the direct test claims that value.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/testing.md`,
	`rules/include/common/testing/unit-runtime.md` before its first governed action.
