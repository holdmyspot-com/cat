---
paths:
  - "*.java"
  - "**/*.java"
  - "pom.xml"
  - "**/pom.xml"
---
# Java Convention Loading Index

## Design Goals

- Load the focused Java conventions needed for the code and decisions currently being changed.

## Guidance

When changing Maven configuration, build scripts, dependencies, or build/lint/test command policy, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/build.md`.

When changing Maven configuration, dependencies, plugins, or build scripts, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/maven.md`.

When writing or editing Java source code, including its formatting, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-style.md`.

When declaring or renaming a Java type, field, constant, method, constructor, parameter, record component, local
variable, catch parameter, or a key or symbolic value that stays inside Java, choosing a declared type, writing a
non-obvious literal or a timeout, or deciding whether to extract a value into a constant, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-style/declarations.md`.

When adding a member to a Java type, or moving or reordering Java declarations, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-style/member-order.md`.

When writing Java code that names a type or static constant not yet imported in its file, or adding, removing, or
reordering imports, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/java-style/imports.md`.

When writing or changing a Java `if`, `else`, `switch`, loop, `try` statement, guard clause, or condition, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-style/control-flow.md`.

When writing or changing a Java lambda, method chain, string or text-block literal, regular expression, non-ASCII
character, string building or comparison, or `Path.of()` call, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-style/expressions.md`.

When writing or editing a Java comment, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/java-style/comments.md`.

When writing or editing Javadoc, Javadoc links/imports, or Java API documentation, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-javadoc.md`.

When handling strings, text blocks, external string checks, or return-value validation, or writing Java code that
returns `null`, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/java-strings.md`.

When validating Java parameters or using Requirements.java APIs such as `requireThat()`, `that()`, or `checkIf()`, lazy
load `${CAT_PROJECT_ROOT}/.cat/rules/include/requirements-api.md`.

When defining CLI or hook output and error behavior, or writing CLI or hook code that writes to standard output or
standard error, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/cli-output.md`.

When declaring a Java type, constructor, method, field, or variable, changing its visibility or declared type, or
writing Java code that starts an external tool, or when designing classes, interfaces, scopes, constructors, builders,
method visibility, entrypoint classes, or business-logic boundaries, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-class-design.md`.

When deciding whether a Java type is thread-safe, implementing or extending a type whose documentation states the thread
safety of its implementations, adding or removing synchronization or thread-safe types such as concurrent collections,
atomics, or locks, or writing or editing a type's thread-safety documentation, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-class-design/thread-safety.md`.

When designing or changing a pouch scope, its lifetime, owned dependencies, child scopes, or resource ownership,
constructing a shared service, policy, or resource such as a `JsonMapper` in code that can reach a pouch scope, or
choosing how a class obtains its dependencies, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/pouch.md`.

When changing environment access or hook/process boundaries, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/environment.md`.

When throwing, catching, declaring, or wrapping exceptions, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-exceptions.md`.

When adding or changing logging, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/logging.md`.

When writing or changing Java tests, assertions, exception tests, test documentation, testability seams, clocks, or
thread-related test behavior, lazy load `${CAT_PROJECT_ROOT}/.cat/rules/include/java-testing.md`.

When Java code creates temporary directories or files, or a Java test creates or removes a fixture, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-temporary-resources.md`.

When editing `module-info.java`, module exports/opens, module names, module layout, or Java project layout, or when
creating, moving, or naming a test class, test package, or test module, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/java-modules.md`.

When writing or changing a Java `main` method, or Java code that runs a command on its caller's behalf, lazy load
`${CAT_PROJECT_ROOT}/.cat/rules/include/jlink-launchers/programs.md`.

When a project-owned script or launcher starts Java code, invoke its named module through `java --module-path ... --module
MODULE/MAIN_CLASS`. Do not use the class path (`-cp`, `--class-path`, or Maven `exec.additionalClasspathElements`) to
launch project code. This does not govern build-tool internals or class-loader resource names.
