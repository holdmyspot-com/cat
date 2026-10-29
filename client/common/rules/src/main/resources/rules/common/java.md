---
paths:
  - "*.java"
  - "pom.xml"
  - "**/pom.xml"
---
# Java Rule Routing

## Design Goals

- Route Java-specific guidance after a task enters a Java project or source file, and load each focused rule only when
	its decision arises.

## Guidance

When configuring Logback, lazy load `../include/java/logback.md`.

When writing or reviewing a Maven POM or Maven configuration that refers to a project or root directory, lazy load
`../include/java/maven.md`.

When writing or reviewing SLF4J logging calls, lazy load `../include/java/slf4j.md`.

When writing or reviewing a Java test whose assertion depends on elapsed time, lazy load
`../include/java/test-time.md`.

When writing or reviewing Java code, including tests, that starts an external process or relies on behavior that
can differ between operating systems, lazy load `../include/java/portability.md`.

When writing or reviewing a Java declaration whose type is `Optional`, or a call that passes `Optional` to a method or
constructor, lazy load `../include/java/optional.md`.

When declaring a Java field, record component, parameter, return type, local variable or collection element that holds
a filesystem path, or changing its type, lazy load `../include/java/paths.md`.

When a Java test needs direct access to non-public production behavior to avoid unrelated caller work, lazy load
`../include/java/test-access.md`.

When adding or changing a public or protected declaration in a package that a `module-info.java` exports, changing a
module's `exports` or `requires transitive` directives, or resolving a warning that a type is not exported from a
module, lazy load `../include/java/module-exports.md`.

When writing or editing a Java constructor or instance initializer that assigns a field, lazy load
`../include/java/field-assignment.md`.
