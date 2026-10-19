# Pouch

## Design Goals

- Model dependencies and resources by the lifecycle that owns them, so callers receive stable values and cleanup occurs
	at the correct boundary.
- Ensure code that participates in a pouch-managed lifecycle uses the dependencies configured for that lifecycle rather
	than silently creating competing instances.

## Guidance

Do not use dependency-injection frameworks. Use pouch scope-based service locators for inversion of control. Pass a
scope object through constructors when the class needs values or services owned by that scope; do not unpack scope
components merely to pass them individually through another call chain.

When code has access to a pouch scope, obtain every scope-owned dependency from that scope. Do not construct a
replacement in an application class, helper, or test fixture. This applies to a `JsonMapper` and to any other shared
service, policy, or resource whose configuration, identity, cleanup, or lifetime belongs to the scope. Construct such a
dependency only at its owning scope's composition boundary. A value object, a short-lived operation-local object, or a
dependency that the accessible scope does not own remains local because it has no scope-managed configuration, identity,
cleanup, or lifetime to preserve.

When a pouch scope assembles a concrete implementation for a policy dependency, do so at that scope's composition
boundary. Keep the policy dependent on its narrow contract, not on the selected integration mechanics.

Scopes represent values that remain constant for their lifetime. Model parent/child scopes after resource lifetimes, and
use `try-with-resources` for scopes that own resources. Share common production/test-scope behavior in an abstract scope
instead of duplicating it in each concrete scope.

Model and name a pouch scope after the operation or lifecycle it owns, not after a value it happens to provide. For
example, use `HookScope` for values shared during one hook call and `ToolScope` for values shared during one CLI tool
call. Do not create `JsonScope`, `ProjectRuleScope`, or another value- or feature-named scope merely to expose that
value; make it a dependency of the enclosing lifecycle scope instead. Introduce a nested scope only when a nested
operation has a distinct lifetime, ownership, or cleanup boundary.

Use `AbstractScope` only when a scope is thread-safe or supports child scopes. Otherwise, implement `AutoCloseable` and
define only the non-child lifecycle methods its callers need.

When a scope creates an `AutoCloseable` resource, declare it directly in the try-with-resources header.

```java
try (Process process = new ProcessBuilder(command).start())
{
	return process.waitFor();
}
```

Declare it before the `try` and use `try (resource)` only when conditional assignment, transfer from another scope, or
use after the resource scope requires the broader lifetime. When modifying try-with-resources code, verify that any
resource declared before the `try` has one of these reasons.
