# Mixed operators grouped

## Variables

- **backoff_factor**: The multiplier applied to the base delay, a whole number between 2 and 9

## Scenario

Handle each of the following independent requests. Answer them in order, label each answer with its request number, and
show the complete code for each.

**Request 1.** This Java method decides whether a model call must be retried. Add one more reason to retry: the response
was cut off, which the caller reports as `truncated == true`.

```java
static boolean needsRetry(AtomicLong responseCount, String stopReason)
{
	if (responseCount.longValue() == 0 || stopReason != null && !isTerminal(stopReason))
		return true;
	return false;
}
```

**Request 2.** A teammate asks you to review this TypeScript function before it is merged. Return the version you would
approve.

```typescript
function retryDelay(configured: number | undefined, base: number): number {
	return configured ?? base * {{backoff_factor}};
}
```

## Assertions

### logical_grouping_visible

- **kind**: effect
- **description**: For request 1: Wherever the method's conditions combine `||` with `&&`, parentheses or separate
	statements show that `stopReason != null && !isTerminal(stopReason)` is one operand of `||`, and the method still
	returns `true` for an empty response, for a non-terminal stop reason and for a truncated response, and `false`
	otherwise.

### coalescing_grouping_visible

- **kind**: effect
- **description**: For request 2: The approved version shows with parentheses, or with a named intermediate value, that
	the multiplication is the right operand of `??`, such as `configured ?? (base * {{backoff_factor}})`, and still
	returns `configured` when it is defined and `base * {{backoff_factor}}` otherwise.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/operator-precedence.md`
	before its first governed action.
