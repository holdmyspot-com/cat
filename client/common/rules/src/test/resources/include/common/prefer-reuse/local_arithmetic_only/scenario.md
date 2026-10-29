# Local arithmetic only

## Variables

- **language**: The language of the requested function, such as `Python` or `TypeScript`

## Scenario

Write a {{language}} function that returns the largest of three integers.

## Assertions

### largest_returned

- **kind**: effect
- **description**: Returns a {{language}} function that returns the largest of its three integer arguments, including
	when two or all three are equal.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/prefer-reuse.md` or
	`rules/include/common/preserve-required-distinctions.md` for this request.
