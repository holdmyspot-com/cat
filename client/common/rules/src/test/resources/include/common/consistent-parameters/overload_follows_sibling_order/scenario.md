# Overload follows sibling order

## Design Goals

- Prove that a new overload takes the inputs it shares with its sibling in the sibling's order and under role names,
	and that an edit which leaves every signature unchanged does not reorder an existing operation's parameters.

## Variables

- **event_type**: The name of the class that a hook parses from its standard input, such as `HookEvent` or
	`ToolEvent`.

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** This class is the entry point of a command-line hook. `main` passes the process's standard input to
`handle`. Tests need to call the hook without a process, so add a public overload of `handle` that takes an already
parsed `{{event_type}}` and the `HarnessScope`, and make the existing `handle` delegate to it after it parses the event
and creates the scope. Show the resulting class.

```java
public final class Hooks
{
	public static HookResult handle(JvmScope scope, InputStream event) throws IOException
	{
		{{event_type}} parsed = {{event_type}}.parse(event);
		HarnessScope harness = new HarnessScope(scope, parsed.sessionId());
		return switch (parsed.name())
		{
			case "SessionStart" -> SessionStart.run(harness, parsed);
			case "PostToolUse" -> PostToolUse.run(harness, parsed);
			default -> HookResult.ignored();
		};
	}
}
```

**Request 2.** `Archive.load` fails with a `NullPointerException` when `charset` is `null`. Make a `null` charset mean
UTF-8. Show the corrected method.

```java
public final class Archive
{
	public static void save(Charset charset, Path file, List<String> lines) throws IOException
	{
		Files.write(file, lines, charset);
	}

	public static List<String> load(Path file, Charset charset) throws IOException
	{
		return Files.readAllLines(file, charset);
	}
}
```

## Assertions

### overload_follows_sibling_order

- **kind**: effect
- **description**: For request 1: the new public `handle` overload declares the `HarnessScope` parameter before the
	`{{event_type}}` parameter, as the existing `handle` declares its scope before its event.

### overload_uses_role_names

- **kind**: effect
- **description**: For request 1: the new overload names its `HarnessScope` parameter `scope` and its `{{event_type}}`
	parameter `event`, matching the names of the existing overload's parameters in the same roles.

### unchanged_signature_kept

- **kind**: guard
- **description**: For request 2: the answer's `load` method keeps the parameter order `(Path file, Charset charset)`;
	the answer does not reorder the parameters of `load` or `save`. It may mention that their orders differ.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/consistent-parameters.md` before its first governed action.
