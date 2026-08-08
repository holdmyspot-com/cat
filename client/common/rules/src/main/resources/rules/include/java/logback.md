# Logback Configuration for Java

## Design Goals

- Keep test logging configuration separate from the application's runtime logging configuration.

## Guidance

Use `logback.xml` for the application's runtime logging configuration. In a Maven project, put it in the main
application module at `src/main/resources/logback.xml`.

Use `logback-test.xml` for test-specific logging configuration, at `src/test/resources/logback-test.xml` in Maven
modules that need it. Do not put `logback.xml` in test resources.
