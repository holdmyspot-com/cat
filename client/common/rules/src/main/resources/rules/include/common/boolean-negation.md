# Boolean Negation

## Design Goals

- A boolean expression states its condition with the fewest negations among the forms that are equivalent for every
	value its operands can take, so a reader learns when it is true without cancelling negations mentally.
<!-- Counting negations does not choose between `!(x instanceof Y)` and `(x instanceof Y) == false`, which hold one
each, or between `x` and `x == true`, which hold none, so this goal names the form. -->
- A boolean test, such as a type test, a predicate call or a boolean variable, is written as the test itself or under
	the logical not rather than compared with a boolean literal, so a reader recognizes every test and its negation in the
	same form.
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

Do not compare a boolean test, such as a type test, a predicate call or a boolean variable, with a boolean literal
(`true`, `false`, `True` or `False`) through `==`, `!=`, `===`, `!==`, `is` or `is not`, with the literal on either
side. When the comparison holds where the test is true, write the test itself; when it holds where the test is false,
apply the language's logical not (such as `!` or `not`) to the test, parenthesized where the operator would otherwise
bind to part of it. For example:

- Java or JavaScript: write `!(value instanceof Duration)` rather than `(value instanceof Duration) == false`,
	`(value instanceof Duration) === false` or `(value instanceof Duration) != true`.
- Python: write `not isinstance(value, timedelta)` rather than `isinstance(value, timedelta) == False` or
	`isinstance(value, timedelta) is False`.
- TypeScript, where `isExpired` returns a `boolean`: write `if (isExpired(token))` rather than
	`if (isExpired(token) === true)` or `if (true === isExpired(token))`.

Rewrite only when both forms give the same result for every value the operands can take. Keep the original form when
they differ. For example:

- When a floating-point operand can be NaN, `!(ratio > 0.5)` is true for NaN while `ratio <= 0.5` is false.
- When an operand compared with a boolean literal can hold a value that is not a boolean, the comparison and the
	logical not can disagree: in JavaScript, `"0" == false` is true while `!"0"` is false, and `undefined === false` is
	false while `!undefined` is true; in Python, `[] == False` and `None == False` are false while `not []` and
	`not None` are true.
- When an operand is a nullable boolean, a comparison with a literal can give a definite result for null where the
	logical not does not: in SQL, `flag IS NOT TRUE` is true when `flag` is null, while `NOT flag` is null, so a `WHERE`
	clause keeps the row under the first form and drops it under the second.

Rewrite the expression without reordering the surrounding branches or statements. A negation that no equivalent
expression avoids stays as written, such as `value != null` or the condition of an early return like `if (!valid)`.
