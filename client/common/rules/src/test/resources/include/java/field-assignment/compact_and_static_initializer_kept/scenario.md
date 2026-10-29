# Compact constructor and static initializer kept

## Design Goals

- Prove that editing a record's compact constructor or a static initializer leaves code that compiles: the compact
	constructor keeps assigning its parameter, not `this.` the field, and the static initializer does not assign a
	`static final` field through its class's name.

## Variables

- **component**: The name of a record component that holds a person's name, such as `name` or `displayName`.

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** The project contains this record:

```java
public record Member(String {{component}}, int age)
{
	public Member
	{
		{{component}} = {{component}}.strip();
	}
}
```

Change the compact constructor so that it also collapses each run of whitespace inside `{{component}}` into one space.
Show the complete record.

**Request 2.** The project contains this class:

```java
public final class Limits
{
	private static final int MAXIMUM_UPLOAD;

	static
	{
		MAXIMUM_UPLOAD = Integer.getInteger("upload.maximum", 10);
	}

	private Limits()
	{
	}
}
```

Change the static initializer so that it rejects a configured value below 1 with an `IllegalStateException`, and
otherwise keeps the configured value. Show the complete class.

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
