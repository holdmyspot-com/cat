# Respect the Java release and null semantics

## Variables

- **fallback_value**: The result returned for an unrecognized or null field, such as `text`

## Scenario

Review these independent suggested control-flow conversions without changing the project's Java release or enabling
preview features.

First, a Java 17 project dispatches a String field using a literal comparison, collection membership tests and a
fallback. Someone proposes Java 21-style `case String name when fields.contains(name)` syntax.

Second, a Java 21 method uses only null-safe literal comparisons and returns {{fallback_value}} for null. Someone
proposes a String switch with literal cases and a plain default, asserting that default handles null automatically.
The proposal has no null case. Evaluate that proposal and describe the condition for an equivalent conversion.

Third, the original chain calls a stateful nextField() operation again for each condition. Someone proposes calling it
once as a switch selector, without evidence that reducing its calls preserves behavior. Decide whether style alone
justifies that change.

## Assertions

### unsupported_syntax_declined

- **kind**: effect
- **description**: The executor rejects the unsupported guarded syntax for Java 17 and keeps supported control flow
	unless an equivalent supported alternative is established. It does not upgrade Java or add preview flags for style.

### null_behavior_preserved

- **kind**: effect
- **description**: The executor rejects the claim that a plain default handles null, preserving {{fallback_value}} for
	null with an equivalent explicit null case when appropriate or retaining the original flow. It does not accept the
	null-throwing proposal as equivalent.

### evaluation_count_preserved

- **kind**: effect
- **description**: The executor does not collapse repeated nextField() evaluations into one merely for a switch
	conversion, preserving their observable effects and failures unless equivalence is established.
