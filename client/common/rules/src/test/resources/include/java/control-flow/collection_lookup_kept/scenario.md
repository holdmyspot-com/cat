# Collection lookup kept

## Variables

- **setting**: The name of a setting that a plugin persists, such as `endpoint`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Tidy up the control flow of this Java method so that it follows our conventions, and show the revised
class. `PERSISTED_SETTINGS` is used nowhere else.

```java
final class PluginSettings
{
	private static final Set<String> PERSISTED_SETTINGS = Set.of("queue", "user", "product", "{{setting}}");

	static void validate(Map<String, String> settings)
	{
		for (Map.Entry<String, String> entry : settings.entrySet())
		{
			if (!PERSISTED_SETTINGS.contains(entry.getKey()))
				throw new IllegalArgumentException("Unknown plugin setting: " + entry.getKey());
		}
	}
}
```

**Request 2.** Tidy up the control flow of this Java method in the same way, and show the revised class.

```java
final class CommandAliases
{
	private static final Map<String, String> ALIASES = Map.of("ls", "list", "rm", "remove", "mv", "move");

	static boolean isAlias(String name)
	{
		if (name == null)
			return false;
		return ALIASES.containsKey(name);
	}
}
```

**Request 3.** Tidy up the control flow of this Java method in the same way, and show the revised method.

```java
static boolean isSecret(String key)
{
	return key.equals("token") || key.equals("password") || key.equals("{{setting}}_key");
}
```

**Request 4.** Tidy up the control flow of `isSupported` in the same way, and show the revised class.

```java
final class ExportFormats
{
	private static final Set<String> FORMATS = Set.of("csv", "json", "{{setting}}");

	static String usage()
	{
		return "Supported formats: " + String.join(", ", new TreeSet<>(FORMATS));
	}

	static boolean isSupported(String format)
	{
		return format.equals("csv") || format.equals("json") || format.equals("{{setting}}");
	}
}
```

## Assertions

### set_lookup_kept

- **kind**: effect
- **description**: For request 1: The revised class still tests the key with `PERSISTED_SETTINGS.contains(...)` and
  does not replace that test with a `switch` or another construct that lists the set's elements.

### map_lookup_kept

- **kind**: effect
- **description**: For request 2: The revised class still tests the name with `ALIASES.containsKey(...)` and does not
  replace that test with a `switch` or another construct that lists the map's keys.

### equals_chain_switched

- **kind**: guard
- **description**: For request 3: The revised method replaces the `equals()` chain with a `switch` expression on `key`
  that has one case per accepted name, each yielding `true`, and `false` from `default`.

### existing_collection_used

- **kind**: effect
- **description**: For request 4: The revised `isSupported` tests membership with `FORMATS.contains(...)` rather than
  a `switch` or another construct that lists the formats again.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/java/control-flow.md`
  before its first governed action.
