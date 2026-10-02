## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

### Request 1

A unit test takes 19 seconds. It creates three 50-outcome batches, publishes a category decision, assigns the next
trial, retries publication, and repairs a missing assignment index. A direct test already checks category selection
from aggregate counts in less than a second. A separate, larger campaign test already executes the same publication
and assignment after those three batches; its assertions would fail if the selected category or assignment reference
were wrong. That larger test does not check retry or missing-index repair. Decide what the 19-second test must still do
to preserve its distinct claims while reducing total test work. State the evidence needed before calling the 150
retained outcomes necessary.

### Request 2

A unit test decodes 500 records to assert which page comes next. A separate 700-record test already crosses that page
boundary and asserts the next-page token that the smaller test checks. A direct pagination-policy test can decide the
next page from a lightweight count and token. The 500-record test also checks recovery from a missing page index, which
the other tests do not exercise. Decide how to retain coverage with the least total decoding and setup work.

### Request 3

A unit test provides 500 items to verify that the public API rejects item 501. Another 700-item test passes through
that count while exercising a different output, but none of its assertions would fail if the API accepted item 501.
No direct policy test establishes the public rejection. Decide whether the 500-item test can be removed or reduced to
one item, and what would justify retaining its runtime.

## Assertions

### existing-coverage-before-fixture-claim

For request 1: The agent maps each claim to an existing failure-capable assertion before deciding whether the
150-outcome fixture is needed. It does not infer that the separate 19-second test is necessary merely because the policy
uses three batches. The aggregate selector check proves the selection decision; the larger campaign check proves the
stated publication and assignment interaction.

### unique-recovery-claim

For request 1: The agent keeps retry and missing-index recovery covered. It looks for a focused production
responsibility or narrow test-access boundary that lets those behaviors be tested with fewer records, rather than
dropping the assertions or repeating the full predecessor campaign. It retains expensive integration only for a distinct
claim that such a focused check and the existing campaign check cannot prove.

### shared-claim

For request 2: The agent recognizes that the existing 700-record assertion and direct policy test already cover the
next-page choice. It does not keep 500 decoded records solely to repeat that choice.

### distinct-recovery

For request 2: The agent preserves missing-index recovery in a focused check at the smallest real production boundary
capable of proving it, or identifies a distinct interaction that still requires an integrated fixture. It compares total
suite work rather than moving all decoding into a second large test.

### boundary-remains-proved

For request 3: The agent keeps a failure-capable check of the exact public limit using the required input count. It does
not treat path traversal in the 700-item test as proof, or replace the 500-item public-boundary claim with a one-item
processing test.

### necessary-work-only

For request 3: The agent still removes unrelated per-item work where the boundary does not require it. It accepts a
longer measured runtime only if further reduction would lose the rejection claim or violate quality and readability
constraints.

### guidance-selection

The retained events establish that the executor selects `rules/include/common/testing.md`,
`rules/include/common/testing/unit-runtime.md` before its first governed action.
