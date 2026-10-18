## CAT Javadoc Conventions

## Design Goals

- Apply CAT-specific Javadoc checks and companion-type conventions in addition to the portable plugin guidance.
- Let a reader scan a Javadoc comment's parameters and its exceptions as columns: within each group of `@param` tags and
  each group of `@throws` tags, every description starts in the same column.

## Guidance

### Format

Write every Javadoc comment in this form, including a one-sentence comment:

```java
/**
 * Single line documentation.
 */
```

Do not put the opening delimiter, documentation text, and closing delimiter on one line.

### Tag Alignment

When writing or editing a Javadoc comment, align the descriptions of its `@param` tags with each other, and the
descriptions of its `@throws` tags with each other. Pad with spaces after each parameter name or exception type so
every description in the group starts one space after the group's longest name or type. Align each group on its own:
do not pad one group to match the other, and do not pad `@return` to match either group. A group with one tag needs no
padding.

```java
/**
 * Finds the last turn for the selected prompt in transcript order.
 *
 * @param transcriptReader reader used to open the child transcript
 * @param transcript       path to the child transcript containing the turns
 * @param promptId         value of {@code promptId} to match in the child transcript's user records
 * @return starting user record's UUID, or null when no matching turn is found
 * @throws NullPointerException     if any required argument is null
 * @throws IllegalArgumentException if {@code promptId} is blank
 * @throws IOException              if the transcript cannot be read
 */
```

### Companion Types

When a companion type exists specifically to expose, adapt, configure, test, or otherwise support one target type, its
class description must link to that target type. This includes `<Target>Access` interfaces and `<Target>Secrets`
façades. Link only relationships needed to understand the companion's purpose, not incidental collaborators.

### Requirements.java Assertions

Do not document `AssertionError` for internal invariants enforced with `assert that(...).elseThrow()`; assertions are
development-time checks, not part of the normal API contract.

When Javadoc exception grammar is in scope, run the build-time check that rejects a singular `@throws` predicate
governing multiple `{@code ...}` parameters. Do not substitute an unbounded text search for this tag-bounded check.

```java
/**
 * Creates a platform.
 *
 * @param id a non-blank identifier used in distribution filenames
 */
Platform(String id)
{
	assert that(id, "id").isNotBlank().elseThrow();
	this.id = id;
}

/**
 * Creates a platform.
 *
 * @param id a non-blank identifier used in distribution filenames
 * @throws NullPointerException     if {@code id} is null
 * @throws IllegalArgumentException if {@code id} is blank
 */
public Platform(String id)
{
	requireThat(id, "id").isNotBlank();
	this.id = id;
}
```
