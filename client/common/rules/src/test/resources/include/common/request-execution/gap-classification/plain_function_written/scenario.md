# Plain function written

## Variables

- **language**: The programming language of the requested function, such as `Python` or `JavaScript`

## Scenario

Write a {{language}} function that returns the number of vowels (a, e, i, o, u, in either case) in a string.

## Assertions

### vowel_count_returned

- **kind**: effect
- **description**: Returns a {{language}} function that counts the vowels a, e, i, o and u in either case and no other
	characters.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/request-execution/gap-classification.md` for this
	request.
