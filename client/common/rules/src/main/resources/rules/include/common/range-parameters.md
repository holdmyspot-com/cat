# Range Parameters

## Design Goals

- Let a caller who knows the language's standard library pass a range to a new or changed API without reading its
	documentation to learn which bounds it includes: the API's bounds follow the language's conventional range form.
- Keep a different convention that an external contract fixes at the boundary that it governs, so the rest of the
	code uses one range form.

## Guidance

Before defining an operation, such as a function, method, constructor, or command, whose parameters specify a range,
or changing such parameters, identify the convention that the language's standard library uses for the same kind of
range. A range is a pair of bounds, or a start and a length, that selects part of a sequence or interval: indexes,
offsets, positions, line or column numbers, or times. Use that convention's inclusivity, base, parameter order and
naming. In Java, a range is half-open, `[fromInclusive, toExclusive)`, with zero-based indexes, as in
`List.subList(fromIndex, toIndex)`, `String.substring(beginIndex, endIndex)` and `Arrays.copyOfRange(original, from,
to)`. Name each bound so that its inclusivity is visible where the language's convention does not already make it
obvious, such as `fromInclusive` and `toExclusive`. Return a range in the same form that the operation accepts.

For example, this Java method takes an inclusive end, which a caller used to `subList` would pass one too high:

```java
public List<String> lines(int first, int last);
```

Following the standard library's convention, it takes a half-open range:

```java
public List<String> lines(int fromInclusive, int toExclusive);
```

When an external contract fixes another convention, such as a file format, protocol, or user-facing display that
numbers lines from 1 or gives an inclusive end, keep that convention only in the code that reads or writes that
contract, convert it to the language's convention there, and document the conversion.

When an existing operation uses another convention, change it only when the task already changes that operation's
signature or the user asks for it, and update every caller in the same change. Otherwise keep it and report the
inconsistency and the change that would remove it.
