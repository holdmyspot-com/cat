# No announcement before reads

## Variables

- **example**: An example of a valid identifier, a UUID written in lower case such as
	`3f2b8c1e-9d4a-4c6e-8b1f-2a7d5e9c0b13`

## Scenario

Implement `parse_id` in `ids.py` so it parses a UUID such as `{{example}}` and returns it as a UUID object, raising
`ValueError` for text that is not a UUID.

## Assertions

### routes_before_commentary

- **kind**: effect
- **description**: The retained events show that, before the executor reads `rules/include/common/prefer-reuse.md`, it
	sends no text that states an intended inspection, plan, implementation, test, or conclusion about this task. A generic
	acknowledgement that names no task action is allowed.

### parser_delivered

- **kind**: effect
- **description**: After the turn, `parse_id` returns a UUID object equal to `{{example}}` for that text and raises
	`ValueError` for text that is not a UUID.
