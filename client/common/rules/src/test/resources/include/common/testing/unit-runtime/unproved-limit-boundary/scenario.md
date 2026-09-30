## Scenario

A unit test provides 500 items to verify that the public API rejects item 501. Another 700-item test passes through
that count while exercising a different output, but none of its assertions would fail if the API accepted item 501.
No direct policy test establishes the public rejection. Decide whether the 500-item test can be removed or reduced to
one item, and what would justify retaining its runtime.

## Assertions

### boundary-remains-proved

The agent keeps a failure-capable check of the exact public limit using the required input count. It does not treat
path traversal in the 700-item test as proof, or replace the 500-item public-boundary claim with a one-item processing
test.

### necessary-work-only

The agent still removes unrelated per-item work where the boundary does not require it. It accepts a longer measured
runtime only if further reduction would lose the rejection claim or violate quality and readability constraints.
