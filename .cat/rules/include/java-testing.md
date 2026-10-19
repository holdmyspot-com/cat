## Java Testing

## Design Goals

- Use self-contained Java tests that demonstrate behavior without duplicating external-tool coverage.
- Keep every Java test eligible for the project's configured parallel execution; test isolation must come from owned
	inputs and collaborators, not from reducing concurrency.
- Keep smoke scenarios focused on integration or lifecycle behavior not already established by a unit test.

## Guidance

Apply the bundled `rules/include/common/backward-design.md` rule for backward design and iterative generalization.

Use TestNG to discover and run tests. Use Requirements.java `requireThat()` validators for assertions; do not use TestNG
assertion helpers. Assert observable values and behavior, not merely that a result is non-null.

Document every test method with the behavior it verifies.

### Exceptions

For an expected exception, use TestNG's `expectedExceptions` and, when relevant, `expectedExceptionsMessageRegExp`. The
regular expression matches the complete message, so wrap a substring in `.*`. For an accepting case, call the method
directly; an unexpected exception fails the test.

Declare only the narrowest checked exception each test or fixture helper can propagate. Never declare `throws Exception`
or `throws Throwable`; inspect the invoked APIs and declare their concrete checked type, such as `IOException`.

### Isolation and Testability

Do not replace JVM-global streams or read production environment state in a Java test. Test production logic by calling
Java classes directly. Use Bats only to test the command-line interface, such as its packaged launchers,
installer, and shell bootstrap; where that interface starts a harness, substitute a fake harness executable.

No Java test, including an opt-in integration test, may invoke a Runner implementation, packaged runner or launcher,
native harness process, or model. When a test verifies evaluator, CLI, policy, persistence, or status behavior, inject
typed static evidence at the evaluator boundary. No Bats test may start a real harness or model either. Test how CAT
reads a harness's output against output recorded from that harness, with its source and version documented.

Do not disable or narrow configured test parallelism for a class, method, group, suite, or invocation. In particular,
do not use TestNG `singleThreaded = true` or any equivalent serial-execution annotation or configuration. When a test
cannot run concurrently, identify the shared mutable dependency, move it behind an explicitly supplied collaborator or
give each test an isolated instance, and test that owned boundary. A serial test can hide the interference without
making the production or test design safe under the project's required execution model.

Do not use `Thread.sleep()` in tests; trigger the required condition directly.

Make every test double model only the collaborator behavior that the test claims to observe. For example, a test that
checks an outgoing protocol request may supply request and response streams, but it must not invent process-tree,
shutdown, retry, or ownership behavior merely to make an unrelated production cleanup path run. Use a collaborator
whose ordinary lifecycle already satisfies that cleanup, or add a narrow owned seam. A lifecycle test is different: it
may model lifecycle behavior because the test must exercise and assert that behavior directly.

Do not add a smoke scenario solely to repeat an observable contract already covered by a unit test. Retain a smoke
scenario only when it exercises a distinct integration or lifecycle boundary that the unit test does not reach.

### Meaningful Assertions

Use typed validators rather than derived booleans:

```java
requireThat(path, "path").isRegularFile();
requireThat(result.output(), "output").contains("expected text");
```

Keep test fixtures in a dedicated fixture directory when they are shared or non-trivial.
