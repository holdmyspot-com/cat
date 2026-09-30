# Logback Configuration for Java

## Design Goals

- Keep test logging configuration separate from the application's runtime logging configuration.

## Guidance

Put a test-specific Logback configuration in the test resources directory as `logback-test.xml`. Do not put
`logback.xml` in test resources; use that name only for a runtime configuration in main resources.
