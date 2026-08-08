# Java Method-Chain Layout

## Design Goals

- Keep Java method chains visually continuous without forbidding a readable wrapped layout.

## Guidance

Do not put spaces or tabs between a completed expression in a Java method chain and the following `.` method selector.
For example, write `.stream().map(...)`, not `.stream() .map(...)`. When wrapping a chain, a newline before `.` is
allowed and preferred over inline whitespace:

```java
return values.stream()
	.map(this::transform)
	.toList();
```
