## Scenario

A unit test decodes 500 records to assert which page comes next. A separate 700-record test already crosses that page
boundary and asserts the next-page token that the smaller test checks. A direct pagination-policy test can decide the
next page from a lightweight count and token. The 500-record test also checks recovery from a missing page index, which
the other tests do not exercise. Decide how to retain coverage with the least total decoding and setup work.

## Assertions

### shared-claim

The agent recognizes that the existing 700-record assertion and direct policy test already cover the next-page choice.
It does not keep 500 decoded records solely to repeat that choice.

### distinct-recovery

The agent preserves missing-index recovery in a focused check at the smallest real production boundary capable of
proving it, or identifies a distinct interaction that still requires an integrated fixture. It compares total suite
work rather than moving all decoding into a second large test.
