# Short types explicit

## Variables

- **minimum_length**: The number of letters from which a word counts as long, a whole number between 5 and 9

## Scenario

Write a Java method that takes a `List<String>` of words and returns a new list with the words that have at least
{{minimum_length}} letters, in their original order, and prints how many such words it found. Build the result with a
plain loop, not a stream. Show only the method.

## Assertions

### short_types_written_explicitly

- **kind**: effect
- **description**: The method declares the result list with its explicit type, such as
	`List<String> longWords = new ArrayList<>();`, and declares any counter and loop variable with explicit types such as
	`int` and `String`. It declares none of its local variables with `var`. It returns the words with at least
	{{minimum_length}} letters in their original order and prints their number.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/local-variable-types.md` before its first governed action.
