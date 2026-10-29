# Boolean Negation

## Design Goals

- A boolean expression states its condition with the fewest negations among the forms that are equivalent for every
	value its operands can take, so a reader learns when it is true without cancelling negations mentally.
<!-- Counting negations does not choose between `!(x instanceof Y)` and `(x instanceof Y) == false`, which hold one
each, so this goal names the form. -->
- A negated `instanceof` test applies the logical not operator to the test rather than comparing the test with a boolean
	literal, so a reader recognizes every negated type test in the same form.
- A rewrite that removes a negation preserves the expression's result for every operand value and the surrounding
	statement's structure, so readability never changes behavior.

## Guidance

When writing or changing a boolean expression, count its negations: each logical not (such as `!` or `not`), each
inequality or negated test (such as `!=`, `!==`, `<>` or `is not`), and each name that denotes the opposite of a
condition (such as `isNotEmpty` or `notFound`). If an equivalent form of the same expression has fewer negations, write
that form. For example:

- When comparing two conditions, cancel a negation on each side: write
	`(authorization == DENIED) == (failure == null)` rather than `(authorization == DENIED) != (failure != null)`. In
	general, `P != not Q` is `P == Q`, and `not P != not Q` is `P != Q`.
- Apply a negation to the comparison it negates: write `count == 0` rather than `!(count != 0)`, and `count <= 0`
	rather than `!(count > 0)` when `count` is an integer.
- Use the positive predicate when one exists: write `isEmpty(list)` rather than `!isNotEmpty(list)`.
- Distribute a negation when that removes negations: write `a && b` rather than `!(!a || !b)`.

Negate an `instanceof` test, in any language that has the operator, by applying the logical not operator to the
parenthesized test rather than comparing the test with a boolean literal. For example, write
`!(value instanceof Duration)` rather than `(value instanceof Duration) == false`,
`(value instanceof Duration) === false` or `(value instanceof Duration) != true`.

Rewrite only when both forms give the same result for every value the operands can take. Keep the negated form when
they differ. For example, when a floating-point operand can be NaN, `!(ratio > 0.5)` is true for NaN while
`ratio <= 0.5` is false.

Rewrite the expression without reordering the surrounding branches or statements. A negation that no equivalent
expression avoids stays as written, such as `value != null` or the condition of an early return like `if (!valid)`.
