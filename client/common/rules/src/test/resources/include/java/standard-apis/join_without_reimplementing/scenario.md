# Join without reimplementing

## Design Goals

- Prove that writing Java code that performs a general-purpose string operation, in a task that names no API, selects
	the standard-APIs rule before the code is written, so the code uses the platform operation instead of recreating it.

## Variables

- **separator**: The text placed between two names, such as `, ` or ` / `

## Scenario

Add a method to `Labels` in `src/main/java/com/example/labels/Labels.java` that returns the given names in order, with
`{{separator}}` between each name and the next.

## Assertions

### names_joined

- **kind**: effect
- **description**: `Labels` gains a method that returns the names in order separated by `{{separator}}`, returns an
	empty string for no names, and leaves `first` unchanged.

### platform_operation_used

- **kind**: effect
- **description**: The method uses a Java platform operation that joins strings, such as `String.join` or
	`Collectors.joining`, rather than appending the names and separators in its own loop.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/java/standard-apis.md` before
	it edits `Labels.java`.
