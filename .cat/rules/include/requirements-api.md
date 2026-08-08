## Requirements.java Validation

## Design Goals

- Express meaningful Java input and result contracts consistently with Requirements.java.

## Guidance

Before deciding whether a parameter requires validation, lazy load the bundled
`rules/include/common/parameter-validation.md` rule. When deciding whether an explicit Java null check is unnecessary,
lazy load `rules/include/java/unnecessary-null-checks.md`.

Use Requirements.java instead of handwritten validation when its validator expresses the needed constraint.

Static imports are appropriate for Requirements.java validator entry points. Do not extract a helper that only validates
an argument and forwards one call; inline it.

| Context | Validation |
|---------|------------|
| API boundary | `requireThat(value, "name")` |
| Internal method or invariant | `assert that(value, "name").constraint().elseThrow()` |
| Collected failures | `checkIf()` |

When a constraint applies to a parameter after normalization, assign the normalized value back to the parameter and
validate that parameter with `requireThat()` when Requirements.java supports the constraint. Do not keep a separate
local value solely to hand-code an `if` validation branch. For example:

```java
releaseDirectory = releaseDirectory.toAbsolutePath().normalize();
requireThat(releaseDirectory, "releaseDirectory").isNotEqualTo(releaseDirectory.getRoot(), "filesystemRoot");
Path target = releaseDirectory.getParent();
```

Use `isNotBlank()` for identifiers, names, keys, and paths when empty input is invalid. Use `isNotNull()` when an empty
string is meaningful.

Prefer validator navigation and chaining over manual boolean checks. Use the validator that directly expresses the
filesystem state, such as `isDirectory()` or `isRegularFile()`, instead of a `Files` boolean check:

```java
requireThat(name, "name").isNotBlank();
requireThat(timeout, "timeout").isGreaterThan(Duration.ZERO);
requireThat(directory, "directory").isDirectory();
requireThat(path, "path").isRegularFile();
```

Do not add a redundant `isNotNull()` before a validator that already rejects null. Add it before `isEqualTo()` or
`isNotEqualTo()` when null must be rejected explicitly.

Fail fast for invalid required input. Throw a typed exception for operational failure rather than returning `null` or a
silent fallback. Optional parameters may use documented defaults.

Tests use `requireThat()` assertions rather than TestNG assertion helpers. Use typed validators such as
`requireThat(path, "path").isRegularFile()` instead of validating a derived boolean.
