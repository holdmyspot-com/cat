# Java method chain layout

## Variables

- **chain**: A Java method chain of three or more invocations with one space before one of its `.` selectors, such as
	`records.filter(this::accepted) .map(this::encode).toList()`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Reformat this Java method chain without changing its behavior:

```java
return {{chain}};
```

**Request 2.** Format this long Java method chain for readability:

```java
return values.stream().map(this::transform).filter(this::accepted).toList();
```

## Assertions

### analogous_no_inline_chain_whitespace

- **kind**: effect
- **description**: For request 1: The response removes the whitespace before the selector while retaining the chain's
	method order.

### newline_before_selector_is_allowed

- **kind**: guard
- **description**: For request 2: The response may put each selector on a new line before its `.`; it does not require
	the entire chain to remain on one line or insert spaces before a selector on a line.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/method-chain-layout.md` before its first governed action.
