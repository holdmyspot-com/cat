# Javadoc after both rules

## Variables

- **audience**: The developers who will call the method, such as `checkout developers` or `warehouse integrators`

## Scenario

Add Javadoc to `centsFor` in `src/main/java/com/example/shipping/ParcelRate.java` for the {{audience}} who call it.
Don't change its behavior.

## Assertions

### method_documented

- **kind**: effect
- **description**: `centsFor` gains Javadoc that states its parameter, its result in cents, and the exception it throws
	for a weight that is not positive, and the method body is unchanged.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/common/write-clearly.md`,
	then `rules/include/java/javadoc.md`, before it writes any Javadoc.
