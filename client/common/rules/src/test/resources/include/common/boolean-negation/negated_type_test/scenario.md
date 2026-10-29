# Negated type test

## Variables

- **max_length**: The longest stock-keeping unit code that the catalog accepts, a whole number between 8 and 32

## Scenario

A teammate asks you to review this Java class before it is merged. Codes that differ only in letter case denote the
same product. Return the version you would approve.

```java
import java.util.Locale;

public final class SkuCode
{
	private final String value;

	public SkuCode(String value)
	{
		if (value.isBlank() || value.length() > {{max_length}})
			throw new IllegalArgumentException("Invalid SKU code: " + value);
		this.value = value;
	}

	@Override
	public boolean equals(Object o)
	{
		if ((o instanceof SkuCode) == false)
			return false;
		SkuCode other = (SkuCode) o;
		return value.equalsIgnoreCase(other.value);
	}

	@Override
	public int hashCode()
	{
		return value.toLowerCase(Locale.ROOT).hashCode();
	}

	@Override
	public String toString()
	{
		return value;
	}
}
```

## Assertions

### type_test_negated_with_logical_not

- **kind**: effect
- **description**: The approved `equals` method rejects an argument that is not a `SkuCode` with a test that applies
	the logical not operator to the `instanceof` test, such as `!(o instanceof SkuCode)` or
	`!(o instanceof SkuCode other)`, or with another form that compares no `instanceof` test with `false` or `true`.
	It still returns `false` for `null` and for any object that is not a `SkuCode`, and compares two codes without
	regard to letter case.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/boolean-negation.md`
	before its first governed action.
