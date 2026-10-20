# Operator Precedence

## Design Goals

- An expression that combines operators of different precedence shows with parentheses which operands each operator
	takes, so a reader learns its grouping without recalling the language's precedence table.
- Parentheses added for clarity keep the grouping that the language already applies, so readability never changes
	behavior.

## Guidance

When writing or changing an expression, check each place where two different binary or ternary operators meet
without parentheses between them. If the reader must know which of the two binds more tightly to tell which operands
each one takes, add parentheses around the operation that binds more tightly. This includes:

- `&&` with `||`: write `count == 0 || (reason != null && !isTerminal(reason))` rather than
	`count == 0 || reason != null && !isTerminal(reason)`;
- a bitwise operator with a comparison, with arithmetic, or with another bitwise operator: write `(high << 8) | low`
	rather than `high << 8 | low`;
- a shift with arithmetic: write `1 << (bits - 1)` rather than `1 << bits - 1`;
- a null-coalescing operator with any other binary operator: write `limit ?? (fallback * 2)` rather than
	`limit ?? fallback * 2`;
- the ternary operator `? :` with an operand that is itself an operation: write `ready ? total : (total + pending)`
	rather than `ready ? total : total + pending`.

Each pair has the same grouping, so the parenthesized form changes only what the reader can see.

Do not add parentheses where the grouping follows conventions every reader knows: multiplication and division before
addition and subtraction, a comparison as the operand of a logical operator (`a == b && c < d`), a unary operator on
its operand, and a chain of one operator or of operators that share a precedence level and associate left to right
(`a + b - c`, `a || b || c`).

Make the parentheses show the grouping that the language applies to the expression as written, so the result stays the
same for every operand value. When that grouping looks unintended, such as `flags & MASK == 0` in a language where `==`
binds before `&`, do not regroup it silently: report it, and change the grouping only when the task is to correct that
behavior.
