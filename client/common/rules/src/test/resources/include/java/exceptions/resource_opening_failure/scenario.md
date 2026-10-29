# Resource opening failure

## Design Goals

- Prove that a resource's opening failure moves into the try-with-resources statement's own catch clause when the try
	block and `close()` cannot throw the caught exception, and stays in a separate try statement when the try block can.

## Variables

- **exception**: The name of a checked exception that opening a session throws, such as `AuthorizationException` or
	`QuotaExceededException`.

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

In this project, `Session` implements `AutoCloseable`, and its `close()` declares no checked exception.
`src/main/java/com/example/reports/Reports.java` declares `open`, `summarize`, `export` and `denied`; `open` and
`export` throw `{{exception}}`, a checked exception.

1. Simplify the exception handling of `Reports.summary` without changing its behavior.
2. Simplify the exception handling of `Reports.exported` without changing its behavior.

## Assertions

### opening_failure_merged

- **kind**: effect
- **description**: Answer 1 declares the session in a try-with-resources header and catches `{{exception}}` in that
	statement's own catch clause, with no separate try around `open(account)`.

### body_failure_not_captured

- **kind**: guard
- **description**: Answer 2 does not catch `{{exception}}` in a catch clause that also covers the call to
	`export(session)`; an exception from `export` still propagates to the caller instead of reaching `denied`.
