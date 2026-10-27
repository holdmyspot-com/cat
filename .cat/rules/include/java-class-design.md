## Java Class Design

## Design Goals

- Apply Java-specific type, visibility, source-set, and resource-scope conventions that implement the project's
	class-design guarantees, with method and type access determined by required callers and the package's module
	exports.

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

A package is non-exported when its module's `module-info.java` does not export it, or exports it only to that module's
own test module. In such a package, make every top-level type `public`. Make a method or nested type `public` when code
outside its enclosing type should invoke or use it, and `private` otherwise. Do not use package-private access for
methods or types. Keep `protected` for a real subclass hook when the existing subclass-access rules require it. These
type rules cover classes, interfaces, enums, records, and annotation interfaces.

For methods and types in packages exported to any other module, and for constructors and fields in all packages, use
the most restrictive visibility that permits the required callers:

| Visibility | Use when |
|------------|----------|
| `public` | Part of the intended external API |
| `protected` | A subclass actually needs access |
| package-private | Only package peers need access |
| `private` | Only this class needs access |

`protected` is meaningless in a `final` class. Choose its replacement using the rules above. After reducing visibility,
remove methods that have no remaining callers.

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
