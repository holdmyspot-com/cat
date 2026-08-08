---
paths:
  - "*.java"
  - "pom.xml"
  - "**/pom.xml"
---
# Java Convention Loading Index

## Design Goals

- Load the focused Java conventions needed for the code and decisions currently being changed.

## Guidance

When changing Maven configuration, build scripts, dependencies, or build/lint/test command policy, lazy load
`../include/build.md`.

When changing Maven configuration, dependencies, plugins, or build scripts, lazy load `../include/maven.md`.

When designing common or harness-specific modules, or deciding whether code should be shared, lazy load
`../include/architecture.md`.

When editing Java source style, imports, naming, formatting, control flow, comments, regex, Unicode, or string
comparison patterns, lazy load `../include/java-style.md`.

When writing or editing Javadoc, Javadoc links/imports, or Java API documentation, lazy load
`../include/java-javadoc.md`.

When handling strings, text blocks, external string checks, or return-value validation, lazy load
`../include/java-strings.md`.

When validating Java parameters or using Requirements.java APIs such as `requireThat()`, `that()`, or `checkIf()`, lazy
load `../include/requirements-api.md`.

When defining, parsing, reading, or processing CAT-owned JSON or YAML, or independently handleable unbounded record or
binary input, lazy load `../include/data.md`.

When defining CLI or hook output and error behavior, lazy load `../include/cli-output.md`.

When designing classes, interfaces, scopes, constructors, builders, method visibility, entrypoint classes, or
business-logic boundaries, lazy load `../include/java-class-design.md`.

When designing or changing a pouch scope, its lifetime, owned dependencies, child scopes, or resource ownership, lazy
load `../include/pouch.md`.

When changing environment access or hook/process boundaries, lazy load `../include/environment.md`.

When throwing, catching, declaring, or wrapping exceptions, lazy load `../include/java-exceptions.md`.

When adding or changing logging, lazy load `../include/logging.md`.

When writing or changing Java tests, assertions, exception tests, test documentation, testability seams, clocks, or
thread-related test behavior, lazy load `../include/java-testing.md`.

When Java code creates temporary directories or files, lazy load `../include/java-temporary-resources.md`.

When editing `module-info.java`, module exports/opens, module names, module layout, or Java project layout, lazy load
`../include/java-modules.md`.
