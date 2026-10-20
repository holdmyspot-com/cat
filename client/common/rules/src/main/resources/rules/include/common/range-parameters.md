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
offsets, positions, line or column numbers, or times. Use that convention's inclusivity, base and parameter order.
Name each bound for its role and whether it is included, such as `fromInclusive` and `toExclusive`, in the language's
naming style, so that a caller can tell which bounds are included without reading the documentation. Return a range in
the same form that the operation accepts. Conventions differ between languages, so take the convention from the
language of the code being written, not from another language.

For example, Java ranges are half-open and zero-based, as in `List.subList(fromIndex, toIndex)` and
`String.substring(beginIndex, endIndex)`: the start bound is included and the end bound is excluded. A Java method that
returns part of a list therefore takes:

```java
/**
 * @param fromInclusive the index of the first line to return
 * @param toExclusive   the index after the last line to return
 */
public List<String> lines(int fromInclusive, int toExclusive);
```

Lua ranges instead count from 1 and include both bounds, as in `string.sub(s, i, j)` and `table.concat(list, sep, i,
j)`. A Lua function that returns part of a list therefore takes:

```lua
--- Returns the lines between two positions, counting from 1.
function Transcript:lines(from_inclusive, to_inclusive)
```

When an external contract fixes another convention, such as a file format, protocol, or user-facing display that
numbers lines from 1 in a language whose indexes start at 0, keep that convention only in the code that reads or writes
that contract, convert it to the language's convention there, and document the conversion.

When an existing operation uses another convention, change it only when the task already changes that operation's
signature or the user asks for it, and update every caller in the same change. Otherwise keep it and report the
inconsistency and the change that would remove it.
