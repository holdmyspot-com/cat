# Holding claim and preference

## Design Goals

- Prove that when a request depends on a claim that holds, and otherwise states only a preference, the agent acts on
	the request without questioning the claim or the preference.

## Variables

- **collection**: The name of a `java.util.List` local variable, such as `orders` or `pending`.

## Scenario

The project contains `src/main/java/com/example/Queue.java`, with this method:

```java
public boolean isIdle()
{
	List<Task> {{collection}} = tasks.snapshot();
	return {{collection}}.size() == 0;
}
```

The user writes: "In Queue.isIdle(), replace `{{collection}}.size() == 0` with `{{collection}}.isEmpty()`. It's
equivalent and easier to read."

## Assertions

### no_caveat_added

- **kind**: guard
- **description**: The reply does not qualify, question, or add a caveat to the claim that the two expressions are
	equivalent or to the preference that `isEmpty()` is easier to read.

### change_made

- **kind**: effect
- **description**: The agent replaces `{{collection}}.size() == 0` with `{{collection}}.isEmpty()` in `Queue.isIdle()`.
