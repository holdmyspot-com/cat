## Java Lambdas, Method Chains, and Strings

## Design Goals

- Keep Java lambdas, method chains, string handling, literals, and fixed paths direct and easy to inspect.

## Guidance

- Write a lambda whose body is one `return` statement or one expression statement that fits on one visual line as an
	expression lambda, without braces or `return`. Keep a block body when that statement wraps onto a second line, when
	the body holds more than one statement, or when its one statement cannot be an expression, such as `throw` or a
	local variable declaration. Also keep it when the lambda's one expression statement returns a value and the lambda is
	passed to an overloaded method: the expression form can then select a different overload, such as
	`executor.submit(() -> future.complete(value))` selecting `submit(Callable)` where the block form selects
	`submit(Runnable)`. This governs lambdas only, not the arms of a `switch`:

	```java
	return withLock(entries -> new QueueCursor(new QueueSnapshot(entries)));
	```

	rather than:

	```java
	return withLock(entries ->
	{
		return new QueueCursor(new QueueSnapshot(entries));
	});
	```
- Prefer direct method chains when each step is clear. Break a chain after `.` when wrapping it across lines.
- Use a text block for multiline or escape-heavy literal content when it removes Java delimiter escaping and makes the
	represented text easier to inspect. Keep a regular string for a short literal whose escapes are few or required by
	the represented content itself. Account for a text block's incidental newline explicitly when it matters.
- Escape regular expressions minimally; prefer Java string escaping only where the regex requires it.
- Use Unicode characters only when they materially improve user-facing text. Use escapes or ASCII in source identifiers
	and control syntax.
- Use `StringBuilder.isEmpty()` rather than `length() == 0`.
- Use `StringJoiner` for delimiter-separated output instead of manually injecting delimiters.
- Use `equals()` for case-sensitive string comparisons and `equalsIgnoreCase()` only when the domain is intentionally
	case-insensitive.
- When comparing a string variable that may be null with a literal, prefer `Objects.equals(variable, "literal")` to
	`"literal".equals(variable)`. When the variable is known to be non-null, use `variable.equals("literal")`.
- When every component of a filesystem path is fixed text, pass one slash-delimited path to `Path.of()`. For example,
	use `Path.of("a/b")`, not `Path.of("a", "b")`. Keep a derived filename in that expression when its parent directories
	are also fixed. Use separate arguments only for independently supplied components whose boundaries matter to the
	operation.
