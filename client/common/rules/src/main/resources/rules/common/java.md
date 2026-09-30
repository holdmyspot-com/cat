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

When writing or reviewing SLF4J logging calls, lazy load `../include/java/slf4j.md`.

When writing or reviewing a Java test whose assertion depends on elapsed time, lazy load
`../include/java/test-time.md`.

When a Java test needs direct access to non-public production behavior to avoid unrelated caller work, lazy load
`../include/java/test-access.md`.
