## Java Declarations

## Design Goals

- Give every Java type, member, variable, parameter, constant, and Java-internal key or symbolic value a name and
	declared type that tell a reader its role, and wrap long declarations consistently.

## Guidance

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
- When choosing type or member visibility or removing unused methods, lazy load
	`${CAT_PROJECT_ROOT}/.cat/rules/include/java-class-design.md`.
- Initialize constant or parameter-independent fields inline. Initialize fields in a constructor only when they depend
	on constructor input or require multi-step setup.
- Use interface types for declarations unless concrete-only operations are required.
- Use `Duration` for time values; do not pass timeouts as ambiguous numeric units.
- In addition to what the bundled Java Optional rule permits, a record component, or a `final` field that a getter
	returns as-is, may be declared `Optional`.
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
- Preserve externally required parameter names. Use `_` for an unused catch parameter; otherwise retain a meaningful
	unused parameter name rather than adding a suppression.
