# Java Control Flow

## Design Goals

- Make closed sets of named Java cases explicit, complete, and easy to extend.
- Keep each closed set defined in one place, so that converting a comparison chain never copies a collection's
  elements into a second list that must change with it.

## Guidance

When a Java expression or method classifies one value against a closed set of named alternatives, use a `switch`
expression or statement instead of a chain of `equals()` comparisons joined by `||` or `else if`. When the chain is a
boolean membership predicate, replace it with a `switch` expression on the classified value, with one case per accepted
alternative, `true` for each accepted case, and `false` from `default`. For example:

```java
return switch (relationship)
{
	case "corrects-predecessor" -> true;
	case "reverts-predecessor" -> true;
	default -> false;
};
```

Do not retain an `equals()` chain merely because every accepted alternative produces the same result. Keep ordinary
open-ended predicates, such as range checks, null checks, or independent boolean conditions, as predicates rather than
inventing a switch.

The conversion applies only to alternatives that the comparisons spell out. A lookup in a collection, such as
`Set.contains()` or `Map.containsKey()`, already states the membership test once, and the collection defines the set:
keep the lookup and do not copy the collection's elements into `switch` cases, even when no other code uses the
collection. When the alternatives are also defined as a collection, or the code needs them as one, test membership with
that collection's lookup instead of listing them as `switch` cases.
