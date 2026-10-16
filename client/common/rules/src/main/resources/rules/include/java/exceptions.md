# Java Exception Semantics

## Design Goals

- Keep Java exceptions semantically precise and preserve all meaningful failure context.
- Keep exception handling next to the operation that failed unless later control flow genuinely needs the failure.
- Handle a try-with-resources resource's opening failure in that statement's own catch clause, rather than a separate
  try statement, whenever doing so cannot change which failures the handler receives or when it runs.

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

When a try-with-resources resource's initializer throws an exception that you handle, catch it in that statement's own
catch clause instead of a separate try around the initializer, provided neither the try block nor the resource's
`close()` can throw that exception type. The statement's catch clause also catches that type from the block and from
`close()`, and runs after the resource has closed; keep the separate try when either difference changes the result.
