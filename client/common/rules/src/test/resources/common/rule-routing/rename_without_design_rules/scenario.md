# Rename without design rules

## Variables

- **new_name**: The name that replaces `calc`, such as `total_amount` or `sum_amounts`

## Scenario

Rename `calc` in `report.py` to `{{new_name}}`, updating its call. Change nothing else.

## Assertions

### renamed

- **kind**: effect
- **description**: After the turn, `report.py` defines `{{new_name}}` instead of `calc`, `print_report` calls it, and
	nothing else in the file changed.

### guidance_selection

- **kind**: guard
- **description**: The executor does not select `rules/include/common/backward-design.md` or
	`rules/include/common/design-quality.md` for this request.
