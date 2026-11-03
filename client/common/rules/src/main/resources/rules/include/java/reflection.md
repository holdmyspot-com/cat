---
depends-on:
  - ../common/reflection.md
---
# Java Reflection and Cross-Package Access

## Design Goals

- An agent recognizes each Java reflection form as reflection, so the common reflection rule governs it.
- Java code of the project, including a test, that needs another package's non-public behavior reaches it through a
	SharedSecrets façade owned by that package, and no other code can reach that behavior through the façade or through
	reflection.

## Guidance

In Java, reflection includes `Class.forName` and `ClassLoader.loadClass` used to obtain a class by name, the
`java.lang.reflect` members such as `Method.invoke`, `Field.get`, `Field.set` and `Constructor.newInstance`,
`MethodHandles.Lookup` lookups such as `findVirtual`, `findStatic`, `findGetter` and `privateLookupIn`, and
`setAccessible` or `trySetAccessible`. An `opens` directive or `--add-opens` option that lets the project's own code
reflect into another of its packages relaxes the platform's access control for that code.

A test whose package has the same name as the production package reaches its package-private members directly; it
needs no façade. When Java code in a different package, or in another module of the same project, needs the
non-public behavior, use a SharedSecrets façade:

- In the module that owns the behavior, declare a public access interface that has only the typed operations the
	caller needs, and a public façade class that holds one implementation of it in a private static field and has a
	static setter and a static getter for that field. Give each topic its own interface and façade; do not use a
	generic registry that callers query by type token or name.
- Put the interface and the façade in a package that the module does not export. When another named module must use
	them, add a qualified `exports … to` directive for that module only. Do not add `opens` for them.
- In the class that owns the behavior, register the implementation from its static initializer by passing a lambda,
	method reference, or nested class that calls the existing operation. That code may call the class's private
	members, so the operation keeps its visibility.
- In the getter, when the field is unset, force the owning class's static initializer to run with
	`MethodHandles.lookup().ensureInitialized(Owner.class)`, read the field again, and throw an `AssertionError` that
	names the owning class when it is still unset. Put that call in one private helper of the façade that every getter
	uses. The owning class must be accessible from the façade's package so the getter can name it with a class literal.

That `ensureInitialized` call is the one reflective operation a façade needs; no other reflection is allowed in it or
its callers.
