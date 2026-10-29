# Edited constructor qualified

## Design Goals

- Prove that editing a Java constructor qualifies every field assignment in it, including lines the task does not
	otherwise change, while the class's behaviour and a method the task does not touch stay as they were.

## Variables

- **limit_field**: The name of a field that holds the most items a basket accepts, such as `capacity` or `limit`.

## Scenario

Change the constructor of `Basket` in `src/main/java/com/example/shop/Basket.java` so that it also rejects a
`{{limit_field}}` that is less than 1, with `requireThat({{limit_field}}, "{{limit_field}}").isPositive();`. Show the
complete class.

## Assertions

### every_constructor_assignment_qualified

- **kind**: effect
- **description**: The revised constructor assigns `items` and `remaining` as `this.items = ...` and
	`this.remaining = ...`, keeps `owner` and `{{limit_field}}` assigned through `this.`, and contains no field
	assignment written without `this.`.

### behaviour_unchanged

- **kind**: guard
- **description**: Apart from the new check on `{{limit_field}}`, every new `Basket` starts with the field values it
	had before: `owner` and `{{limit_field}}` from the arguments, `items` an empty `ArrayList` created with capacity
	`{{limit_field}}`, and `remaining` equal to `{{limit_field}}`; the existing `owner` check is kept.

### untouched_methods_kept

- **kind**: guard
- **description**: The revised class keeps `clear()` and `add()` exactly as they were, including the unqualified
	`remaining = {{limit_field}};` in `clear()` and `--remaining;` in `add()`.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/java/field-assignment.md`
	before its first governed action.
