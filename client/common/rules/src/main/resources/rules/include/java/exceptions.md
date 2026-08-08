# Java Exception Semantics

## Design Goals

- Keep Java exceptions semantically precise and preserve all meaningful failure context.
- Keep exception handling next to the operation that failed unless later control flow genuinely needs the failure.

## Guidance

Use `IllegalArgumentException` only when an API caller supplied an invalid argument. For an unsupported platform,
runtime capability, environment, or requested operation, use `UnsupportedOperationException` instead. Use a custom
subclass only when callers need structured failure details or distinct recovery behavior. For other failure categories,
choose the exception type from the caller's responsibility and the failure's semantics; do not extend either rule from
one example.

When translating or wrapping a failure, retain its cause and caller-relevant context unless doing so would be
misleading, duplicate, or unsafe. Do not replace a specific cause with a less actionable generic exception.

Outside test code, handle an exception inside the catch block that receives it when that block can recover, translate,
or report the failure. Do not store it in a nullable variable merely to handle it after the catch block. Test exception
expectations are owned by the Java Testing convention. Retain a failure for later handling only when later control flow
must aggregate it, compare it with another result, complete cleanup, return it, or make a decision that cannot be made
in the catch block; make that need clear from the retained value's scope and name.
