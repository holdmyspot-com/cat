# Lines use a half-open range

## Design Goals

- Prove that a new Java method whose parameters select part of a sequence takes a zero-based, half-open range, and that
	an edit which leaves an existing method's signature unchanged keeps that method's inclusive bounds.

## Variables

- **sequence_name**: The name of the list of text lines that the class holds, such as `lines` or `entries`.

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Add a public method to `Transcript` that returns a contiguous part of `{{sequence_name}}`, selected by
the positions where it starts and ends, so that a viewer can page through the transcript. Show the method with its
Javadoc.

```java
public final class Transcript
{
	private final List<String> {{sequence_name}};

	public Transcript(List<String> {{sequence_name}})
	{
		this.{{sequence_name}} = List.copyOf({{sequence_name}});
	}

	public int size()
	{
		return {{sequence_name}}.size();
	}
}
```

**Request 2.** `Report.count` throws `IndexOutOfBoundsException` when `last` equals the list's size. Make it reject that
call with an `IllegalArgumentException` that names `last` instead. Show the corrected method.

```java
public final class Report
{
	/**
	 * @param first the index of the first row to count
	 * @param last  the index of the last row to count, inclusive
	 */
	public static int count(List<Row> rows, int first, int last)
	{
		return (int) rows.subList(first, last + 1).stream().filter(Row::isFlagged).count();
	}
}
```

## Assertions

### half_open_bounds

- **kind**: effect
- **description**: For request 1: the new method takes a start bound that it includes and an end bound that it
	excludes, both zero-based indexes into `{{sequence_name}}`, as `List.subList` does; its parameter names or Javadoc
	state which bound is excluded.

### existing_signature_kept

- **kind**: guard
- **description**: For request 2: the answer's `count` method keeps the parameters `(List<Row> rows, int first, int
	last)` and keeps `last` inclusive. It may mention that the method differs from Java's half-open convention.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/range-parameters.md`
	before its first governed action.
