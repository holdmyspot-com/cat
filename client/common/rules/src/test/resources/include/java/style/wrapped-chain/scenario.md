## Scenario

Format this long Java method chain for readability:

```java
return values.stream().map(this::transform).filter(this::accepted).toList();
```

## Assertions

### newline-before-selector-is-allowed

The response may put each selector on a new line before its `.`; it does not require the entire chain to remain on one
line or insert spaces before a selector on a line.
