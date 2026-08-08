## Exceptions

## Design Goals

- Report failures with the most specific exception type and actionable context appropriate to their cause.

## Guidance

Use `IllegalStateException` only for a queryable state that callers could check before invoking the method. Internal
invariants belong in assertions; catch `AssertionError` only when it is explicitly part of top-level error reporting.
Never catch `Error` generically.

For a caller-visible exception, preserve the specific cause and state the failed condition, safe relevant input or
state, and corrective action when the caller can act on it. Do not replace a known cause with a generic wrapper or a
message that leaves the caller unable to identify what to correct.

Tests must also declare the narrowest checked exception they expect to use.

When pouch is available and a checked exception must cross an unchecked boundary, use
`WrappedCheckedException.wrap(cause)` rather than a generic `RuntimeException` wrapper.
