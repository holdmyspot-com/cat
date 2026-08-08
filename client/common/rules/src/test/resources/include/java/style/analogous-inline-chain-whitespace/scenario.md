## Scenario

Reformat this Java method chain without changing its behavior:

```java
return records.filter(this::accepted) .map(this::encode).toList();
```

## Assertions

### analogous-no-inline-chain-whitespace

The response removes the whitespace between `accepted)` and `.map(...)` while retaining the chain's method order.
