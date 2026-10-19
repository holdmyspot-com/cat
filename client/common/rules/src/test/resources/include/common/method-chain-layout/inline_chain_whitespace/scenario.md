# Inline chain whitespace

## Variables

- **chain**: A TypeScript method chain of three or more invocations with one space before one of its `.` selectors,
	such as `items.filter(isActive) .map(toLabel).join(", ")`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Reformat this TypeScript method chain without changing its behavior:

```typescript
return {{chain}};
```

**Request 2.** Format this long Go method chain for readability, keeping it valid Go:

```go
return NewQuery(table).Where(activeFilter).OrderBy("name").Limit(pageSize).Build()
```

## Assertions

### no_inline_chain_whitespace

- **kind**: effect
- **description**: For request 1: The response removes the whitespace before the selector while retaining the chain's
	method order; it does not retain whitespace before a selector on the same line.

### language_permitted_wrapping

- **kind**: guard
- **description**: For request 2: Any line break in the chain follows a `.`, as Go requires; the response does not
	begin a continuation line with `.` or insert spaces before a selector on a line.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/method-chain-layout.md`
	before its first governed action.
