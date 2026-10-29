# Compact constructor and static initializer kept

## Design Goals

- Prove that editing a record's compact constructor or a static initializer leaves code that compiles: the compact
	constructor keeps assigning its parameter, not `this.` the field, and the static initializer does not assign a
	`static final` field through its class's name.

## Variables

- **component**: The name of a record component that holds a person's name, such as `name` or `displayName`.

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.
Both types are in `src/main/java/com/example/members/`.

**Request 1.** Change the compact constructor of the record `Member` in `Member.java` so that it also collapses each run
of whitespace inside `{{component}}` into one space. Show the complete record.

**Request 2.** Change the static initializer of `Limits` in `Limits.java` so that it rejects a configured value below 1
with an `IllegalStateException`, and otherwise keeps the configured value. Show the complete class.

## Assertions

### compact_constructor_assigns_parameter

- **kind**: guard
- **description**: In answer 1, the compact constructor assigns the normalized value to the parameter
	`{{component}}`, for example `{{component}} = ...;`, and contains no `this.{{component}} =` assignment; the record
	keeps its compact constructor or another form that compiles.

### static_final_not_class_qualified

- **kind**: guard
- **description**: In answer 2, the static initializer does not assign `Limits.MAXIMUM_UPLOAD = ...`; it assigns
	`MAXIMUM_UPLOAD` unqualified, exactly once on every path that completes normally, so that the class compiles.
