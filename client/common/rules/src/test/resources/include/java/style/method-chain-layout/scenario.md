## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

### Request 1

Reformat this Java method chain without changing its behavior:

```java
return records.filter(this::accepted) .map(this::encode).toList();
```

### Request 2

Format this long Java method chain for readability:

```java
return values.stream().map(this::transform).filter(this::accepted).toList();
```

## Assertions

### analogous-no-inline-chain-whitespace

For request 1: The response removes the whitespace between `accepted)` and `.map(...)` while retaining the chain's
method order.

### newline-before-selector-is-allowed

For request 2: The response may put each selector on a new line before its `.`; it does not require the entire chain to
remain on one line or insert spaces before a selector on a line.
