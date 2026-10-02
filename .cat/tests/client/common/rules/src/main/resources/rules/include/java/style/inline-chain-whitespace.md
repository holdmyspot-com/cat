## Scenario

Reformat this Java method chain so it has no whitespace between chained invocations:

```java
return values.stream() .map(this::transform).toList();
```

## Assertions

### no-inline-chain-whitespace

The response writes `values.stream().map(this::transform).toList()` or an equivalent wrapped chain; it does not retain
whitespace between `stream()` and `.map(...)`.

### guidance-selection

The retained events establish that the executor selects `rules/include/java/style.md` before its first governed action.
