## Java Style

## Design Goals

- Keep Java code and explanatory comments readable, consistent, and stable under routine maintenance.

## Guidance

Let Checkstyle and PMD enforce mechanical formatting. Apply these conventions when their intent requires judgment.

### Layout and Names

- Follow `.editorconfig` for shared whitespace settings. When manually wrapping Java, follow the project's existing
	surrounding style.
- Source and configuration that CAT fully generates must follow the same applicable conventions as checked-in artifacts
	of that type, including `.editorconfig` whitespace settings. Verify generated text whose formatting is semantically or
	operationally relevant with an exact-output test. This does not require reformatting an artifact owned by an external
	generator that CAT only patches.
- Use smart tabs; one tab represents two spaces. Do not align code with spaces.
- Use exactly one blank line between the last import and the following element.
- Use Allman braces. Put `else`, `catch`, `finally`, and `do`/`while` continuations on a new line after the closing
	brace.
- When the parameters of a method, constructor, or record do not fit on one line, put as many parameters on each line as
	fit within the line length limit, and wrap only between parameters. Indent every continuation line one tab past the
	declaration's first line. Do not align parameters with spaces under the opening parenthesis or an earlier parameter:

	```java
	public record UsageReport(ProductId product, Harness harness, @JsonProperty("harness_version") String harnessVersion,
		String turn, @JsonProperty(value = "output_tokens", required = true) long outputTokens)
	{
	```

	rather than:

	```java
	public record UsageReport(ProductId product, Harness harness,
	                          @JsonProperty("harness_version") String harnessVersion,
		String turn, @JsonProperty(value = "output_tokens", required = true) long outputTokens)
	{
	```
- In a try-with-resources header, put each resource declaration on its own line.
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
- Use camelCase for method names, Java variables (local variables, parameters, and instance fields), and keys and
	symbolic values that stay inside Java, such as map keys and string identifiers that no external format defines.
	Static final constants and enum constants keep UPPER_SNAKE_CASE. When a key or symbolic value crosses into
	project-owned JSON, spell it in snake_case at that boundary; for example, the `deliveryResult` field is written as
	the `delivery_result` key. Public, package-private, and private method names are at most 40 characters.
- For a lookup or selection, name the action (`find`, `resolve`, or `select`) and document its search scope, matching
	or selection rule, and no-match or ambiguous-match outcome.
- For parsing, conversion, normalization, or validation, name the input domain and semantic result or boundary. Do not
	use an abstract quality label such as `safe` when the call site cannot tell what the method makes safe.
- Name non-obvious literals and intermediate values after their semantic role, rather than their source or an abstract
	quality label. Prefer widely understood precise terminology unless a specialized term adds a necessary distinction.
	For example, prefer `unquotedLauncherPrefix` and `quotedLauncherPrefix` to `generatedLines` and `safeLines`.
- Treat `stdout` and `stderr` as standard terminology for a process's standard output and standard error. Prefer those
	terms in identifiers, comments, and documentation over expanded names such as `standardErrorOutput`; retain an
	expanded spelling only when an external API or established public type requires it.
- When naming a type, apply the bundled Identifier Naming rule. For example, a class that encapsulates access to Git is
	`Git`, not `GitFixture`, even when its first consumer is a test.
- Name a lifecycle scope after the action it guarantees, rather than the resource type it happens to hold. For example,
	name a temporary-directory scope `cleanup`, not `directories`, so readers see that paths added to it will be deleted.
	Apply this to scopes such as cleanup, locking, rollback, or transactions; an ordinary collection should still be named
	for the values it contains.
- Do not extract an inline value into a constant solely to avoid a literal at its use site. Define a constant only when
	the value has multiple references that express one shared concept, or when the name makes an otherwise non-obvious
	value materially easier to understand. Keep a single-use message or an obvious value inline when a named constant
	would only move it away from the reader. Before extracting, ask: “Would replacing this name with its value make the
	surrounding code meaningfully harder to read?” If not, keep the value inline.
- When choosing type or member visibility or removing unused methods, lazy load `java-class-design.md`.
- Use interface types for declarations unless concrete-only operations are required.
- Use `Duration` for time values; do not pass timeouts as ambiguous numeric units.

### Declaration Order

Order declarations for top-down reading: type description, static fields, `main(String[] args)`, other public static
methods, non-static fields, constructors regardless of visibility, other public instance methods, then protected,
package-private, and private helpers. Order public methods by reader-facing conceptual or lifecycle flow. Order helpers
as a depth-first implementation narrative: place a caller before the helper that it introduces. Keep helpers shared by
multiple call paths after the implementation section they support rather than promoting them by call count. Keep related
overloads together.

Preserve existing compliant order. Insert a new public method at the relevant API or lifecycle point, and a new helper
after the first caller that introduces it, without reordering unrelated existing declarations. If an existing helper
gains another caller, retain its location unless it clearly interrupts the implementation narrative. Preserve the order
of existing peer methods and shared helpers. Use directional call frequency only when creating a new helper group with
no established order; never use it alone to reorder an established group.

### Imports and Fields

- Use explicit imports; do not use wildcard imports.
- Use an imported simple name for a type reference. Use a fully qualified name only when two required types share the
	same simple name and a local qualification is clearer than changing the surrounding design; do not use one merely to
	avoid adding an import.
- Statically import a constant when its simple name is immediately understandable at its use site and the owner adds no
	necessary context; otherwise retain the qualified reference. For example, statically import `UTF_8` or `CONTINUE`, but
	retain `Locale.ROOT`.
- Import types used only by Javadoc links.
- Initialize constant or parameter-independent fields inline. Initialize fields in a constructor only when they depend
	on constructor input or require multi-step setup.

### Expressions and Control Flow

- Prefer positive conditions. When one alternative immediately returns, throws, breaks, or continues and the remaining
	work spans multiple visual lines, express the exit as a guard clause before that work, even if the guard uses a
	negative condition. This takes precedence over preferring positive conditions. Do not use `else` after a guard clause.
- Separate distinct method phases with blank lines: validation, normalization/setup, primary branching, and final
	failure/return handling. Do not add blank lines within one cohesive phase.
- Use `switch` when selecting among a closed set of values; use `if` when conditions are not simple alternatives.
- In addition to what the bundled Java Optional rule permits, a record component, or a `final` field that a getter
	returns as-is, may be declared `Optional`.
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

### Collections, Loops, and Strings

- Use enhanced `for` loops when an index is not required.
- Use `++` or `--` only when it improves a loop counter; otherwise use explicit arithmetic.
- Prefer direct method chains when each step is clear. Break a chain after `.` when wrapping it across lines.
- Use a text block for multiline or escape-heavy literal content when it removes Java delimiter escaping and makes the
	represented text easier to inspect. Keep a regular string for a short literal whose escapes are few or required by
	the represented content itself. Account for a text block's incidental newline explicitly when it matters.
- Escape regular expressions minimally; prefer Java string escaping only where the regex requires it.
- Use Unicode characters only when they materially improve user-facing text. Use escapes or ASCII in source identifiers
	and control syntax.

### Comments and Parameters

- Comments explain why, constraints, or non-obvious consequences—not what nearby code already says.
- When a comment justifies a deliberate control, lead with the concrete unwanted outcome it prevents and the actor or
	operation that could cause it. Do not describe only the control's mechanics, permissions, or recipients; those facts
	do not tell a reader why the control must remain.
- When a comment explains one component supplying, withholding, transforming, or consuming a value or capability for
	another, name the source component, receiving component, thing, and resulting use or effect. Name the boundary when it
	affects that effect. Do not compress the handoff into an abstract verb such as “retains” or an undefined collective
	such as “subprocesses”.
- Preserve externally required parameter names. Use `_` for an unused catch parameter; otherwise retain a meaningful
	unused parameter name rather than adding a suppression.

### Size and Simplicity

Keep methods under about 80 lines and classes under about 2,000 lines when practical. Extract a coherent responsibility,
not a mechanical fragment. Prefer simple, direct code over abstractions created only for hypothetical reuse.
