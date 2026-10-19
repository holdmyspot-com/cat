# Java inline chain whitespace

## Variables

- **chain**: A Java method chain of three or more invocations with one space before one of its `.` selectors, such as
	`values.stream() .map(this::transform).toList()`

## Scenario

Reformat this Java method chain so it has no whitespace between chained invocations:

```java
return {{chain}};
```

## Assertions

### no_inline_chain_whitespace

- **kind**: effect
- **description**: The response writes the chain without whitespace between its invocations, or an equivalent wrapped
	chain; it does not retain whitespace before a selector on the same line.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/method-chain-layout.md` before its first governed action.
