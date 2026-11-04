# Java Control Flow

## Design Goals

- Make equivalent Java dispatch on one value explicit with switch blocks supported by the project's Java release,
	preserving branch priority, evaluation, null behavior, and failures.
- Keep each membership set defined in one place, using its lookup directly or within guarded dispatch rather than
	copying its elements into a second list that must change with it.

## Guidance

When a Java branch chain classifies the same value, prefer an equivalent `switch` expression or statement supported by
the project's Java release. Prefer an expression when the branches produce one value. This includes chains of
`equals()` comparisons joined by `||` or `else if`. When a boolean membership predicate spells out its accepted
alternatives, use a `switch` expression on the classified value, with one case per accepted alternative, `true` for each
accepted case, and `false` from `default`. For example:

```java
return switch (relationship)
{
	case "corrects-predecessor" -> true;
	case "reverts-predecessor" -> true;
	default -> false;
};
```

Do not retain an `equals()` chain merely because every accepted alternative produces the same result. Keep ordinary
standalone predicates, such as range checks or null checks, and independent boolean conditions as predicates rather
than inventing a switch.

A standalone collection lookup, such as `Set.contains()` or `Map.containsKey()`, already states the membership test
once: keep that lookup rather than wrapping it in a switch. When alternatives are also defined as a collection, or the
code needs them as one, use that collection's lookup instead of listing its elements again in cases, even when no
other code uses the collection. When a Java 21+ branch chain mixes constant alternatives with collection membership on
the same value, use guarded pattern cases that call the authoritative collection's lookup. For a known non-null String
selector, an ordered classification can use:

```java
return switch (field)
{
	case "finished" -> "flag";
	case String name when COUNT_FIELDS.contains(name) -> "count";
	case String name when FILE_FIELDS.contains(name) -> "file";
	default -> "text";
};
```

Before converting, preserve branch order, including which branch wins when collection memberships overlap, and preserve
selector and condition evaluation, short-circuiting, side effects, and failures. A switch evaluates its selector once;
do not silently replace repeated effectful evaluations. Check null behavior explicitly: `default` alone does not match
null. Use an equivalent explicit null case when supported and appropriate, or keep the original control flow when
equivalence cannot be established. Do not upgrade the project's Java release or enable preview features solely for a
style conversion.
