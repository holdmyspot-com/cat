## Java Imports

## Design Goals

- Keep every Java type and constant reference short and unambiguous through explicit imports.

## Guidance

- Use exactly one blank line between the last import and the following element.
- Use explicit imports; do not use wildcard imports.
- Use an imported simple name for a type reference. Use a fully qualified name only when two required types share the
	same simple name and a local qualification is clearer than changing the surrounding design; do not use one merely to
	avoid adding an import.
- Statically import a constant when its simple name is immediately understandable at its use site and the owner adds no
	necessary context; otherwise retain the qualified reference. For example, statically import `UTF_8` or `CONTINUE`, but
	retain `Locale.ROOT`.
- Import types used only by Javadoc links.
