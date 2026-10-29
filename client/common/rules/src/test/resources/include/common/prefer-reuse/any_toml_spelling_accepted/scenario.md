# Any TOML spelling accepted

## Variables

- **language**: The language of the requested function, such as `Python` or `Go`

## Scenario

Write a {{language}} function that reads `config/service.toml` and returns the `timeout` value from its `[server]` table
as an integer. Any way that TOML allows the number to be written is fine; we only care about its value.

## Assertions

### timeout_value_returned

- **kind**: effect
- **description**: The delivered function returns `1500` for the file in the workspace, using an existing TOML parser
	rather than custom text matching.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/prefer-reuse.md`
	before its first governed action.

### no_distinction_route

- **kind**: guard
- **description**: The executor does not select `rules/include/common/preserve-required-distinctions.md` for this
	request.
