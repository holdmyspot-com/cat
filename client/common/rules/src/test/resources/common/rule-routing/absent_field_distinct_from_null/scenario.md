# Absent field distinct from null

## Variables

- **default_port**: The port used when the configuration omits `port`, a whole number from 8000 to 8999

## Scenario

Implement `load_settings` in `settings.py`. It reads a JSON file with an optional `port`. If `port` is missing, use
{{default_port}}. If `port` is present but `null`, the service must not listen on any port, so return `None` for it.

## Assertions

### absent_and_null_distinguished

- **kind**: effect
- **description**: After the turn, `load_settings` returns {{default_port}} as the port when `port` is absent, `None`
	when it is `null`, and the stated port otherwise.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/preserve-required-distinctions.md` before its first governed action.
