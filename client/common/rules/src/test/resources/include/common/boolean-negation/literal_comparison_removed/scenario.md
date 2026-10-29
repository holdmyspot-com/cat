# Literal comparison removed

## Variables

- **grace_days**: The number of days after its due date that an invoice may stay unpaid before a reminder is sent, a
	whole number between 1 and 30

## Scenario

A teammate asks you to review this Python function before it is merged. `paid` is always a `bool`. Return the version
you would approve.

```python
from datetime import date, timedelta

from billing.model import Invoice


def needs_reminder(invoice: object, paid: bool, due: date, today: date) -> bool:
	if isinstance(invoice, Invoice) == False:
		raise TypeError(f"Expected an Invoice, got {type(invoice).__name__}")
	if paid == True:
		return False
	return today > due + timedelta(days={{grace_days}})
```

## Assertions

### boolean_tests_written_directly

- **kind**: effect
- **description**: The approved version rejects an argument that is not an `Invoice` with a test that applies `not` to
	the `isinstance` call, such as `not isinstance(invoice, Invoice)`, and tests `paid` directly, such as `if paid:`. It
	compares neither the `isinstance` call nor `paid` with `True` or `False` through `==`, `!=`, `is` or `is not`. It
	still raises `TypeError` for an argument that is not an `Invoice`, returns `False` for a paid invoice, and otherwise
	returns whether `today` is more than {{grace_days}} days after `due`.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/boolean-negation.md`
	before its first governed action.
