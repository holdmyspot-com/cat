# Failure and absent results

## Design Goals

- Prove that the Javadoc of a method's result names the value that needs no action briefly and states the condition
	of each value the caller must handle directly: a boolean that reports whether an operation succeeded names its
	failure, and a lookup names the case where nothing matches, while a boolean that answers a question about state
	states the condition that makes it `true` without success or failure wording.

## Variables

- **key_name**: The name of the field that identifies an entry, such as `sku` or `code`.

## Scenario

Add Javadoc to this class and to every field, constructor and method in it, since none has any yet. Show the whole
class.

```java
public final class PriceList
{
	private final Map<String, Entry> entries = new HashMap<>();
	private final Instant validUntil;

	public PriceList(Instant validUntil)
	{
		this.validUntil = validUntil;
	}

	public boolean importEntries(List<Entry> batch)
	{
		for (Entry entry : batch)
		{
			Entry earlier = entries.putIfAbsent(entry.{{key_name}}(), entry);
			if (earlier != null && !earlier.equals(entry))
				return false;
		}
		return true;
	}

	public Entry find(String {{key_name}})
	{
		return entries.get({{key_name}});
	}

	public boolean isExpired(Instant now)
	{
		return now.isAfter(validUntil);
	}
}
```

`Entry` is declared elsewhere in the project as `record Entry(String {{key_name}}, BigDecimal price)`. The importer
that calls `importEntries` aborts the whole import when a batch is rejected.

## Assertions

### failure_named_directly

- **kind**: effect
- **description**: The `@return` of `importEntries` names the case that makes it return `false` directly, as two
	entries that have the same `{{key_name}}` but differ, such as in price; it does not describe `false` only as the
	negation of the condition that makes it `true`, such as “`false` otherwise” after “`true` if every repeated
	`{{key_name}}` has an identical entry”.

### success_stated_briefly

- **kind**: effect
- **description**: The `@return` of `importEntries` states the `true` case briefly, such as “`true` on success” or
	“`true` if every entry was imported”, rather than restating, as a condition, that repeated entries are identical.

### partial_import_state

- **kind**: effect
- **description**: The Javadoc of `importEntries` tells the caller that a rejected batch can leave the entries that
	came before the conflicting one in the price list.

### absent_entry_named

- **kind**: effect
- **description**: The `@return` of `find` names the case in which it returns `null`: no entry has the given
	`{{key_name}}`.

### predicate_condition_only

- **kind**: guard
- **description**: The `@return` of `isExpired` states the condition that makes it `true`, that `now` is after the
	price list's validity end, and neither its `@return` nor its description calls either value a success or a
	failure.
