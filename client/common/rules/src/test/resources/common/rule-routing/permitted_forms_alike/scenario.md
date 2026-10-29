# Permitted forms alike

## Variables

- **default_port**: The port used when the configuration gives none, a whole number from 8000 to 8999

## Scenario

Implement `load_settings` in `settings.py`. It reads a JSON file with an optional integer `port`. If `port` is missing or
`null`, use {{default_port}}; the two cases are meant to behave the same.

## Assertions

### default_applied

- **kind**: effect
- **description**: After the turn, `load_settings` returns {{default_port}} as the port both when `port` is absent and
	when it is `null`, and returns the stated port otherwise.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/preserve-required-distinctions.md` for this
	request.
