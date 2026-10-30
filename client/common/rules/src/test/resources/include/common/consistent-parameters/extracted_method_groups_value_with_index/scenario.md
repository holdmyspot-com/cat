# Extracted method groups each value with its index

## Design Goals

- Prove that a new method which takes two values, each with the index that locates it, declares each value immediately
	before its own index and lists both pairs in the same order, even when the calling code declares them in another
	order.

## Variables

- **row_type**: The name of the class that holds one row of a transcript, such as `JsonNode` or `Entry`.

## Scenario

`Turns.status` has grown too long. Move the block that sums the token counts between the origin row and the marker row
into a new private method of `Turns` that returns the sum, and make `status` call it. Show the resulting class.

```java
public final class Turns
{
	public static String status(List<{{row_type}}> chain)
	{
		{{row_type}} origin = chain.getFirst();
		{{row_type}} marker = null;
		int markerIndex = -1;
		for (int i = chain.size() - 1; i >= 0; --i)
		{
			if (chain.get(i).isMarker())
			{
				marker = chain.get(i);
				markerIndex = i;
				break;
			}
		}
		if (marker == null)
			return "incomplete";
		int originIndex = chain.indexOf(origin);

		long tokens = 0;
		for (int i = originIndex + 1; i < markerIndex; ++i)
			tokens += chain.get(i).tokens();
		if (origin.isCompaction())
			tokens += origin.tokens();
		tokens += marker.tokens();

		if (tokens > 100_000)
			return "over budget";
		return "within budget";
	}
}
```

## Assertions

### value_precedes_its_index

- **kind**: effect
- **description**: The new private method declares the origin row parameter immediately before the origin index
	parameter and the marker row parameter immediately before the marker index parameter, so that its parameters read
	in the form `(chain, origin, originIndex, marker, markerIndex)` up to names; it does not declare them in the order
	in which `status` declares the local variables, such as `(chain, origin, marker, markerIndex, originIndex)`.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/consistent-parameters.md` before its first governed action.
