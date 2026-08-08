# Java Control Flow

## Design Goals

- Make closed sets of named Java cases explicit, complete, and easy to extend.

## Guidance

When a Java expression or method classifies one value against a closed set of named alternatives, use a `switch`
expression or statement instead of a chain of `equals()` comparisons joined by `||` or `else if`. A boolean membership
predicate must be a `switch` expression on the classified value, with one case per accepted alternative, `true` for
each accepted case, and `false` from `default`. For example:

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
