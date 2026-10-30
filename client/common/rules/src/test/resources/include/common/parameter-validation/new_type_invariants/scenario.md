# New type invariants

## Design Goals

- Prove that defining a type whose constructor stores values, in a task that does not ask for any checks, selects the
	parameter-validation rule before the type is written, so the constructor establishes the type's invariants.

## Variables

- **type_name**: The name of the new class, such as `Stay` or `Reservation`

## Scenario

Add a class `{{type_name}}` to `booking.py` that holds a guest's name, a check-in date and a check-out date, and has a
method that returns the number of nights.

## Assertions

### nights_returned

- **kind**: effect
- **description**: `booking.py` gains a class `{{type_name}}` that stores the guest's name and both dates and returns
	the number of days from check-in to check-out as its number of nights. `print_stay` is unchanged.

### invariants_established

- **kind**: effect
- **description**: The constructor of `{{type_name}}` rejects a check-out date that is not after the check-in date
	before it stores the values, so no `{{type_name}}` can report zero or a negative number of nights.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/parameter-validation.md` before it edits `booking.py`.
