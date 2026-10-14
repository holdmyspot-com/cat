## CAT Javadoc Conventions

## Design Goals

- Apply CAT-specific Javadoc checks and companion-type conventions in addition to the portable plugin guidance.
- List a method's argument-validation exceptions first, so a caller finds what it must pass before what can fail
  afterwards.

## Guidance

### Format

Write every Javadoc comment in this form, including a one-sentence comment:

```java
/**
 * Single line documentation.
 */
```

Do not put the opening delimiter, documentation text, and closing delimiter on one line.

### Companion Types

When a companion type exists specifically to expose, adapt, configure, test, or otherwise support one target type, its
class description must link to that target type. This includes `<Target>Access` interfaces and `<Target>Secrets`
façades. Link only relationships needed to understand the companion's purpose, not incidental collaborators.

### Exception Order

List the `@throws` tags for invalid arguments, such as `NullPointerException` and `IllegalArgumentException`, before
every other exception type, such as `IllegalStateException` or `IOException`.

```java
/**
 * Reads a campaign.
 *
 * @param path the campaign file
 * @return the campaign
 * @throws NullPointerException     if {@code path} is null
 * @throws IllegalArgumentException if {@code path} is not absolute
 * @throws IllegalStateException    if the store is closed
 * @throws IOException              if the file cannot be read
 */
```

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
 * @throws NullPointerException if {@code id} is null
 * @throws IllegalArgumentException if {@code id} is blank
 */
public Platform(String id)
{
	requireThat(id, "id").isNotBlank();
	this.id = id;
}
```
