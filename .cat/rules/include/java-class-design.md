## Java Class Design

## Design Goals

- Apply Java-specific type, visibility, source-set, and resource-scope conventions that implement the project's
	class-design guarantees.

## Guidance

### Interfaces and Abstract Classes

Interfaces define contracts. Put shared implementation, including derived convenience methods, in an abstract class. Do
not implement an interface method in an abstract class with a placeholder (`null`, an empty value, or a no-op) just
because subclasses will override it; leave it abstract until that class can implement the contract correctly.

Use interface `default` methods only for behavior with one correct, stateless implementation. Do not use them to retain
a superseded API contract.

### External Tool Access

Implement an external-tool access boundary with a focused `ProcessBuilder` access class. It owns the command, working
directory, environment, process lifecycle, output handling, and tool-specific failures. Keep CAT policy and
interpretation outside that class. Apply the same implementation boundary to test fixtures unless process behavior is
the test subject.

### Test-Support Ownership

Place a Java helper used only by tests in its owning module's test source set. When multiple modules need that support,
package it as a test-JAR and depend on it with test scope. Move it into main only after identifying an actual production
consumer; do not promote it merely to make another module's tests compile.

### Visibility and Dead Code

Use the most restrictive visibility that permits the required callers:

| Visibility | Use when |
|------------|----------|
| `public` | Part of the intended external API |
| `protected` | A subclass actually needs access |
| package-private | Only package peers need access |
| `private` | Only this class needs access |

`protected` is meaningless in a `final` class. Make it `private`, or package-private when package peers call it. After
reducing visibility, remove methods that have no remaining callers.

### Declaration Types

Declare variables, fields, parameters, and return types using the most general interface that supplies the required
operations. Use a concrete type only when its concrete-only operations are required.

```java
List<String> names = new ArrayList<>();
Map<String, Integer> index = new HashMap<>();
```

### Error Handling Scopes

When a `main()` method owns a resource scope and handles errors, keep the catch blocks inside that scope. Do not create
a second equivalent scope in a catch block when the original scope remains open.
