---
depends-on:
  - ../documentation.md
  - ./conditions.md
---

# Declared Values

## Design Goals

- When code declares a variable outside a function body, such as a field, constant, or module-level variable, and its
	name, type, and initial value do not tell a reader unfamiliar with the project what the value represents, what
	decides that value, or what the code does with it, the declaration carries an explanation that answers each of those
	questions; a declaration whose name, type, and value already answer them carries no comment that restates them.

## Guidance

When declaring a variable outside a function body, or changing the value or contents of an existing one, judge it as a
reader who sees only the declaration and has not read the code that uses it. Explain at the declaration each of the
following that its name, type, and initial value leave that reader unable to state:

- **What the value represents:** the record, message, file, external system, or domain set its contents come from, with
	the unit and the scope that a quantity covers.
- **What decides the value:** the limit, specification, measurement, or selection criterion that sets a scalar value or
	admits a collection's entries, so a maintainer can tell whether to change the value and whether a new candidate
	belongs.
- **What the code does with it:** the decision or behavior it controls, such as whether listed entries are required,
	allowed, kept, removed, or ignored, when the name does not already say so.

For example, `FIELDS = {"hook_event_name", "turn_id", "transcript_path"}` says neither which input these fields belong
to nor why exactly these are listed. Its declaration must state that they are the top-level hook-input fields that the
handler parses and that it skips every other field. Give the variable a name that carries as much of that meaning as it
can, then document the rest. A constructor-assigned field named `clock` whose type is a clock needs no comment.

Describe the value by its domain meaning, not by restating its name, container type, or literal contents. When the value
comes from an external source, such as a documented protocol limit or the fields an external tool emits, name that
source. Write the explanation in the language's documentation-comment form for the declaration when it has one, and
otherwise in a comment immediately above the declaration.

When changing an existing variable's value or contents, check that its explanation still holds and correct it in the
same change.
