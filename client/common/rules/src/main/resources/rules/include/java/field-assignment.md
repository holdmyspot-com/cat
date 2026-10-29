# Java Field Assignment

## Design Goals

- When an agent writes or edits a Java constructor or instance initializer, every field that its body assigns is named
	through `this.` for an instance field or through the declaring class's simple name for a static field, so that a
	reader tells a field from a parameter or local variable at the assignment, while code the agent does not write or
	edit keeps its form.
- The qualified form never makes code stop compiling: a record's compact constructor and a static initializer keep the
	form that Java accepts there.

## Guidance

When you write or edit a Java constructor or instance initializer block (`{ ... }`), name every field that its body
assigns:

- Assign an instance field as `this.x = ...;`, including a blank `final` field.
- Assign a static field as `ClassName.x = ...;`, using the simple name of the class that declares the field. For a
	static field inherited from a superclass, use the superclass's name, not the current class's.
- Assign a field of the enclosing instance from an inner class's constructor as `Outer.this.x = ...;`.
- When a subclass field hides an inherited instance field, `this.x` assigns the subclass's field; assign the inherited
	one as `super.x = ...;`.

This covers compound assignments and increments too, such as `this.total += amount;` and `Counter.created++;`. It
governs only the field being assigned; a field read elsewhere in the statement may stay unqualified.

```java
// Preferred
Account(String owner)
{
	this.owner = owner;
	Account.opened = opened + 1;
}

// Rejected
Account(String owner)
{
	this.owner = owner;
	opened = opened + 1;
}
```

When you edit a constructor or instance initializer, qualify every unqualified field assignment in it, including lines
that the task does not otherwise change. Do not change the form of field assignments in methods, or in constructors and
initializers that you do not write or edit.

Two places are outside this rule:

- In a record's compact constructor, such as `Person { name = name.strip(); }`, `name` is the constructor's parameter,
	not the field. Java copies each parameter into its field after the body runs, and assigning `this.name` there does
	not compile. Keep those parameter assignments as they are.
- Leave assignments in a static initializer (`static { ... }`) as they are. Assigning a `static final` field there as
	`ClassName.S = ...;` does not compile.
