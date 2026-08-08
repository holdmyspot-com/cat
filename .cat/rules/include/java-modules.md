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
    └── <test package directories>
```

Resource-only modules do not need `module-info.java`. Java implementation modules must declare one.

### Module Descriptors

Name a module after its implementation namespace. Declare direct module dependencies with `requires`. Export only
packages intended for use by other modules; keep implementation details unexported.

```java
module io.github.cowwoc.cat.codex.cli
{
	requires io.github.cowwoc.cat.common.cli;
	requires io.github.cowwoc.requirements13.java;

	exports io.github.cowwoc.cat.codex;
}
```

Use `opens` only when a runtime library genuinely requires deep reflection. Do not widen exports or add test-only
production APIs merely to test implementation details; test through the smallest existing accessible behavior.

### Shared Secrets and Reflection

Use the SharedSecrets approach through a topic-specific façade in the module whose internals it exposes. Place every
public access interface and `<Topic>Secrets` façade in that module's unexported `<module-base-package>.secrets` package,
such as `io.github.cowwoc.cat.distribution.secrets`. Do not export a secrets package by default. If a named external
module must use a specific secret, add a qualified export to that module—and no others:

```java
exports io.github.cowwoc.cat.distribution.secrets to other.module;
```

A module may define multiple façades; do not centralize unrelated access points in another module. The façade exposes
only a narrow typed operation that delegates to existing production behavior with its most restrictive valid visibility.
When the owning class creates and registers the callback, lambda, or method reference, it may invoke that class's
private members; do not widen those members merely to expose the typed operation. Its owning class registers the
implementation from a static initializer, and the façade initializes that owner lazily before use. Do not open a module
to another module solely to initialize or access a shared secret. Each façade owns its typed accessor slot and exposes
typed getter and setter methods; callers do not pass a type token or use a generic shared-secret registry.

In each `<Topic>Secrets` façade, have `access()` call a private `initialize(Class<?> owner)` only while its typed
accessor is null, passing the class that registers the accessor. That helper must load exactly `owner` with
`Class.forName(owner.getName(), true, owner.getClassLoader())` and convert `ClassNotFoundException` to an
`AssertionError` naming the owner. After initialization, `access()` must fail with an `AssertionError` if the owner did
not register its accessor. Do not duplicate owner-specific reflective initialization in each façade.

Do not use reflection, including `Class.forName()`, `java.lang.reflect`, accessibility overrides, or reflective member
lookup/invocation. The sole exception is a SharedSecrets façade whose only reflective operation initializes the class
that registers its access implementation. Do not use reflection to bypass module boundaries, access private state, or
create test-only seams.

### Tests

Do not widen a production constructor, method, field, type, or module export solely for test access. Test through
accessible behavior when it proves the required contract. When a test must directly exercise existing non-public
behavior, expose only a narrow, typed SharedSecrets façade that delegates to that behavior; do not add a test-only
production API, adapter, or mutable seam.

When choosing or reviewing production accessibility, determine the narrowest visibility from production callers only; do
not treat test callers as justification for a public, protected, package-private, exported, or otherwise wider API. If
tests are the only callers requiring non-public behavior, keep that behavior at its production-required visibility and
provide a narrow typed SharedSecrets access path instead.

Tests use Maven and TestNG. Keep them under `src/test/java` in the corresponding package hierarchy. Run the complete
quality gate with `client/mvnw -Dmaven.build.cache.enabled=false -f client/pom.xml verify`; `test` and `compile` do not
run every configured validation.
