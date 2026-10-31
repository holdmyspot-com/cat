## Java Modules and Layout

## Design Goals

- Maintain modular Java boundaries, focused module layout, and controlled access to implementation internals.

## Guidance

### Maven Layout

Use Maven's standard layout:

```text
<module>/
├── pom.xml
├── src/main/java/
│   ├── module-info.java
│   └── <package directories>
└── src/test/java/
    ├── module-info.java
    └── <test package directories>
```

Resource-only modules do not need `module-info.java`. Java implementation modules must declare one, and so must their
tests.

### Test Module and Package Names

- Name a test module after the main module it tests, with `.test` appended: `com.holdmyspot.cat.codex.cli.test` tests
	`com.holdmyspot.cat.codex.cli`.
- Name a test package after the main package it tests, with `.test` appended: `CodexRunnerTest`, which tests
	`com.holdmyspot.cat.codex.runner.CodexRunner`, belongs to `com.holdmyspot.cat.codex.runner.test`, and a test of
	the base package `com.holdmyspot.cat.codex` belongs to `com.holdmyspot.cat.codex.test`. Place a test in the
	package of the main type it tests, even when that differs from the package of related tests.
- Place a test-support package that tests no main package, such as fakes or shared contracts, under the package whose
	tests use it, also ending in `.test`: `com.holdmyspot.cat.common.runner.fakes.test`.
- Place test resources that a test reads by a relative name in the resource directory of that test's package, such as
	`com/holdmyspot/cat/common/optimizer/proposal/test/`. Resource directories count as packages, and a test module
	must not contain a package that another module also contains.

Export each test package that contains TestNG tests to `org.testng`, and export a test-support package only to the test
modules that reuse it.

### Module Descriptors

Name a module after its implementation namespace. Declare direct module dependencies with `requires`. Export only
packages intended for use by other modules; keep implementation details unexported.

```java
module com.holdmyspot.cat.codex.cli
{
	requires com.holdmyspot.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;

	exports com.holdmyspot.cat.codex;
}
```

Use `opens` only when a runtime library genuinely requires deep reflection. Do not widen exports to other modules or add
test-only production APIs merely to test implementation details; a qualified export to the module's own test module
follows the rules under Tests.

When editing a module's exports or opens, giving a test or another module access to a member that it cannot reach,
or writing code that uses reflection, lazy load `./java-modules/shared-secrets.md`.

### Tests

Test through accessible behavior when it proves the required contract. When a test must directly exercise behavior that
its test module cannot reach, decide by what other modules can see:

- A package that the main module exports to no module other than its test module, through a qualified export, may make
	public the types and members its tests need. Widening them changes no other module's view. When choosing method or
	type visibility, lazy load `java-class-design.md`.
- A package that other modules can see keeps its members at their production-required visibility. Expose each member
	the tests need through a narrow, typed SharedSecrets façade that delegates to it, and export the secrets package only
	to the test module. A type in such a package that the tests name may become public.
- A test fake that must override a non-public hook cannot use a façade. Make the hook `protected` if its package is
	exported to other modules, or `public` if only the test module can see it.

Do not add a test-only production API, adapter, or mutable seam, and do not export a package to the test module unless
its tests need it.

Tests use Maven and TestNG. Keep them under `src/test/java`, named as described under Test Module and Package Names.
Run the complete quality gate with `client/mvnw -q -f client/pom.xml verify`; `test` and `compile` do not run every
configured validation.
