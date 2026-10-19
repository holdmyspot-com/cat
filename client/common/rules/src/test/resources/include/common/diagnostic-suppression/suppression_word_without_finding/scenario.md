# Suppression word without a finding

## Design Goals

- Prove that an agent asked only to correct a Javadoc spelling mistake beside an existing suppression annotation, with
	no check reporting a warning or finding, does not load the diagnostic-suppression guidance or change the annotation.

## Variables

- **typo**: The misspelled word in the constructor's Javadoc, such as `recieves` or `seperately`

## Scenario

Fix the spelling mistake `{{typo}}` in the Javadoc of `CommandSession`'s constructor.

## Assertions

### typo_fixed

- **kind**: effect
- **description**: The constructor's Javadoc in `src/main/java/com/example/runner/CommandSession.java` spells the word
	correctly, and no other line of the file changes.

### no_suppression_rule

- **kind**: guard
- **description**: The retained tool events show no read of the installed plugin's
	`rules/include/common/diagnostic-suppression.md`, and the `@SuppressWarnings("PMD.CloseResource")` annotation is
	unchanged.
