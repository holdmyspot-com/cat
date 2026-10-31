## Requirements.java Validation

## Design Goals

- Express meaningful Java input and result contracts consistently with Requirements.java.
- When a Requirements.java validator decides a check whose failure is returned as an error message, the message comes
	from that validator, so the check and its message cannot disagree.

## Guidance

Before deciding whether a parameter requires validation, lazy load the bundled
`${CAT_PLUGIN_ROOT}/rules/include/common/parameter-validation.md` rule. When deciding whether an explicit Java null
check is unnecessary, lazy load `${CAT_PLUGIN_ROOT}/rules/include/java/unnecessary-null-checks.md`.

Use Requirements.java instead of handwritten validation when its validator expresses the needed constraint, unless the
validator's message is inadequate for the person who reads the failure (see below).

When the failure is read by a user who must correct their input, such as command-line arguments, a configuration entry,
or a request field, and the validator's generated message would state only a mechanical constraint (a length, a count,
or a comparison) that does not say what the value means or what to supply, write the check by hand. Throw, or return,
a message that names the expected input in the reader's terms. Keep `requireThat()` or `assert that(...)` for an
internal invariant or programmer error, where the message naming the variable and its actual value is the most useful.
Adding context to a validator with `withContext()` is not a substitute: the message still begins with the mechanical
constraint.

```java
if (args.length < 2)
	throw new IllegalArgumentException("Expected an account operation, product, and optional login ID");
```

rather than:

```java
requireThat(args, "args").length().isGreaterThanOrEqualTo(2);
```

Static imports are appropriate for Requirements.java validator entry points. Do not extract a helper that only validates
an argument and forwards one call; inline it.

| Context | Validation |
|---------|------------|
| API boundary | `requireThat(value, "name")` |
| Internal method or invariant | `assert that(value, "name").constraint().elseThrow()` |
| Failure returned as an error message | `checkIf(value, "name").constraint().elseGetFailures()` |

When every condition being checked can be expressed with a Requirements.java validator and the code returns an error
message on failure (for example in a returned failure or result, a reason string, or a collected list) instead of
throwing, take the message from the validator, unless it is inadequate for its reader as described above. Keep the
`ValidationFailures` that `elseGetFailures()` returns, name the variable after the checked value plus `Failures`, branch
on `isEmpty()`, and read `getMessages()`. A chain with several constraints reports every failed constraint in that one
`ValidationFailures`. To combine the failures of several chains, pass each later chain's `ValidationFailures` to the
first one's `addAll()`. Do not repeat the constraint as a handwritten `if` condition that guards the `checkIf()` call,
hand-format the message from the compared values, or branch on `getMessages()`:

```java
ValidationFailures messageCountFailures = checkIf(messageCount, "messageCount").
	isEqualTo(chain.size(), "chain.size()").elseGetFailures();

if (!messageCountFailures.isEmpty())
{
	String reason = String.join("\n", messageCountFailures.getMessages());
	return failureAt(markerIndex, reason);
}
```

rather than:

```java
if (messageCount != chain.size())
{
	List<String> messages = checkIf(messageCount, "messageCount").isEqualTo(chain.size(), "chain.size()").
		elseGetFailures().getMessages();
	return failureAt(markerIndex, String.join("\n", messages));
}
```

This does not apply when a condition has no Requirements.java validator (write plain code), when a failure throws
(use `requireThat()` or `assert that(...).elseThrow()` as in the table), or when the code needs only a boolean and no
message (do not call `elseGetFailures()` merely to branch).

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

For reference-identity assertions, use `requireThat(actual, "actual").isReferenceEqualTo(expected, "expected")` instead
of `requireThat(actual == expected, "sameInstance").isTrue()`. Use `isReferenceNotEqualTo()` for distinct references and
`isEqualTo()` when comparing values rather than object identity.
