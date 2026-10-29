# Collection lookup kept

## Variables

- **setting**: The name of a setting that a plugin persists, such as `endpoint`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.
The classes are in `src/main/java/com/example/plugin/`.

**Request 1.** Tidy up the control flow of `PluginSettings.validate` in `PluginSettings.java` so that it follows our
conventions, and show the revised class. `PERSISTED_SETTINGS` is used nowhere else.

**Request 2.** Tidy up the control flow of `CommandAliases.isAlias` in `CommandAliases.java` in the same way, and show
the revised class.

**Request 3.** Tidy up the control flow of `Secrets.isSecret` in `Secrets.java` in the same way, and show the revised
method.

**Request 4.** Tidy up the control flow of `ExportFormats.isSupported` in `ExportFormats.java` in the same way, and show
the revised class.

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
