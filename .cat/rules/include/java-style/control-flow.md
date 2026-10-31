## Java Control Flow

## Design Goals

- Keep Java branches, loops, and conditions readable, with each condition evaluated once and only when needed.

## Guidance

- A structure whose body may be a single statement or a block (`if`, `else`, `for`, `while`, and `do`) has braces if
	and only if its condition or its body spans more than one visual line. A one-line statement under a one-line condition
	has no braces; a statement that wraps onto a second line, or a condition that wraps, needs them:

	```java
	if (condition)
		statement;

	if (condition)
	{
		function1(function2(
			function3()));
	}

	if (first &&
		second)
	{
		statement;
	}
	```
- In a try-with-resources header, put each resource declaration on its own line.
- Use enhanced `for` loops when an index is not required.
- Use `++` or `--` only when it improves a loop counter; otherwise use explicit arithmetic.
- Prefer positive conditions. When one alternative immediately returns, throws, breaks, or continues and the remaining
	work spans multiple visual lines, express the exit as a guard clause before that work, even if the guard uses a
	negative condition. This takes precedence over preferring positive conditions. Do not use `else` after a guard clause.
- Use `switch` when selecting among a closed set of values; use `if` when conditions are not simple alternatives.

```java
if (!resolvedTarget.startsWith(artifact) || !Files.isRegularFile(resolvedTarget,
	LinkOption.NOFOLLOW_LINKS))
{
	throw new IOException("Artifact contains an unsafe symbolic-link file");
}

if (ready)
	start();
```

### Lazy Conditional Evaluation

Evaluate a non-trivial condition once when it is first needed if later reachable control flow needs the same result.
Keep that value in the narrowest scope that covers its uses. Do not introduce a variable for a condition evaluated once
unless its name materially improves readability. Order outer branches so inner predicates run only after their enclosing
branch is selected.

```java
boolean linux = normalizedOs.equals("linux");
if (!linux && !normalizedOs.equals("mac os x") && !normalizedOs.equals("darwin"))
	throw new IllegalArgumentException();
if (normalizedArchitecture.equals("amd64") || normalizedArchitecture.equals("x86_64"))
{
	if (linux)
		return LINUX_X64;
	return MACOS_X64;
}
```
