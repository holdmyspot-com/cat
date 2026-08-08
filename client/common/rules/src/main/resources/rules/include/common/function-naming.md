# Function and Method Naming Coverage

## Design Goals

- Make every affected function and method name describe its behavior and target, and verify that across all comparable
  functions and methods rather than by searching for particular name fragments.

## Guidance

When function and method naming is in scope, list every affected function and method in the relevant source languages.
For each one, use its contract, callers, and implementation to state its behavior and target in plain language. Then
check that its name communicates that same behavior and target. In particular, distinguish a method that returns an
already-known value from one that searches for a value, chooses among candidates, converts input, makes a value
canonical, checks a condition, creates something, or removes something. Use equally specific names for other distinct
behaviors.

When a callable's behavior depends on a local representation of an external actor, resource, or boundary, name the
represented semantic target rather than only the local mechanism. In particular, a name for a lifecycle transition must
identify whose state changes when that identity distinguishes the operation; do not reduce a descendant process's
standard-error output to an unspecified output stream. For example, distinguish a parent consuming a forked child's
standard-error output from a method that observes an unrelated output close. A direct operation on a resource with no
distinct represented role does not need an invented actor name.

Use a product or framework name in a callable only when that product's behavior distinguishes the callable's contract.
Otherwise name the applicable generic role, such as a parent process or child process.

Do not use a search for particular name fragments as evidence that the audit is complete. A function or method can have
an inaccurate name without containing an expected fragment. Review every in-scope function and method that performs
the relevant behavior against its contract and implementation.
