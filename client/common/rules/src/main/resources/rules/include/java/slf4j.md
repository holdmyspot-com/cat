# SLF4J Logging

## Design Goals

- Let SLF4J format dynamic message values when the log level is enabled, while preserving an exception's stack trace.

## Guidance

Use `{}` placeholders for dynamic values in SLF4J log messages and pass the values as separate arguments. Do not
insert those values through string concatenation. This applies at every log level, including inside enabled-level
guards. A message without dynamic values needs no placeholders.

For SLF4J logger methods such as `debug` and `warn`, pass an exception as the final argument after the placeholder
values. Keep it out of the placeholder values so SLF4J records the stack trace:

```java
log.debug("Child transcript cannot be inspected: {}", transcript, e);
```
