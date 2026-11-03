# Reflection

## Design Goals

- Code that an agent writes, including a test, reaches a type, function, method, field, or module directly whenever
	direct code gives the same behavior, so the compiler, type checker, and refactoring tools see every use and a
	renamed or removed member fails at build time rather than at runtime.
- Code that keeps reflection uses it only where no direct equivalent exists, and states at the reflective call why
	direct code cannot give that behavior.
- When the project's own code in another package or module, such as a test, needs behavior that the language's
	visibility rules hide from it, it reaches that behavior through a narrow, typed access point owned by the package
	that owns the behavior, and the behavior keeps the visibility that its production callers need.

## Guidance

This rule covers code that you write which looks up or invokes a type, function, method, field, or module by a name
or string at runtime, or which overrides access control to reach a non-public member. Examples are Python's `getattr`,
`setattr` and `importlib.import_module`, JavaScript's `obj[name]()`, and Java's `Class.forName` and `Method.invoke`.
It applies to tests as well as production code. It does not govern choosing a library that reflects internally, such
as a JSON binder or a dependency-injection container; calling that library is not reflection in your code.

Before writing such code, determine whether direct code gives the same behavior. Direct code includes:

- a direct call, field access, or import when the target is known when the code is written;
- a map from a key to a function, constructor, or factory, or a switch over the keys, when the code chooses among a
	fixed set of known targets by a configured or received name;
- a shared interface or base type that each alternative implements, when the code calls the same operation on
	different types;
- the platform's service-discovery facility, such as Java's `ServiceLoader`, when the code discovers implementations
	that are not known when the code is written but implement a type that is.

When direct code gives the same behavior, write it and do not use reflection. Keep reflection only when no direct
equivalent exists, for example when a persisted configuration or published plugin contract names a class that is not
available at build time, or when the language or framework provides the needed operation only reflectively. Then keep
the reflective code to that one operation and write a comment at it that states why direct code cannot give the same
behavior.

Do not use reflection to read or change non-public state, call a non-public member, or replace an implementation,
including in a test. Reach the behavior through its public or package-level API, or through a method that production
code itself uses. First check whether a focused refactor of the owning code can expose the behavior at the visibility
its production callers already need. When the caller is the project's own code in another package or module, such as a
test, and visibility still keeps it from that behavior:

- Give the package that owns the behavior a narrow, typed access point that only the project's own code can reach,
	and make it delegate to the existing operation.
- Keep the operation's visibility as its production callers need it. Do not widen it, add a public test-only method, a
	mutable test seam, or a second implementation of the behavior, or relax the platform's access control for the caller.

When a test reaches a behavior this way instead of through its larger caller, test that caller separately when its
integration is part of the claim.
