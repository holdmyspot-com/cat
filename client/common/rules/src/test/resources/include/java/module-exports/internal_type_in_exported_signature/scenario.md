# Internal type in an exported signature

## Variables

- **queue_class**: The name of a public Java class in a non-exported package that stores usage reports in a file, such
  as `FileReportQueue`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

The project has two Java modules:

```java
module com.example.usage
{
	exports com.example.usage.api;
}

module com.example.usage.test
{
	requires transitive com.example.usage;
	exports com.example.usage.test;
}
```

Package `com.example.usage.api` contains this interface:

```java
public interface ReportQueue
{
	List<Report> drain();
	int size();
}
```

Package `com.example.usage.internal` of module `com.example.usage.test` contains two public classes:

```java
public final class {{queue_class}} implements ReportQueue
{
	public {{queue_class}}(Path file, SigningKeys keys) { ... }
	public List<Report> drain() { ... }
	public int size() { ... }
	public void overwrite(Path file) { ... }
}

/** Holds the private keys that sign usage reports before upload. */
public final class SigningKeys
{
	public PrivateKey currentKey() { ... }
}
```

**Request 1.** The IDE reports `Class '{{queue_class}}' is not exported from module 'com.example.usage.test'` on this
public class in package `com.example.usage.test`. Tests in other modules call only `drain()` and `size()` on the
returned queue. Fix the warning and show the changed code.

```java
public final class UsageFixture
{
	public {{queue_class}} reports() { ... }
}
```

**Request 2.** Review this new public class in package `com.example.usage.internal` before it is merged and report any
module or API concerns.

```java
public final class ReportSpooler
{
	public {{queue_class}} spool(Path directory) { ... }
}
```

## Assertions

### accessible_type_named

- **kind**: effect
- **description**: For request 1: The fix changes the return type of `UsageFixture.reports()` to `ReportQueue` or
  another type that module `com.example.usage.test` exports, and does not leave `{{queue_class}}` in that signature.

### internal_package_kept_unexported

- **kind**: guard
- **description**: For request 1: The fix does not export package `com.example.usage.internal`, does not move
  `SigningKeys` into an exported package, and does not make `{{queue_class}}` or `SigningKeys` accessible outside the
  module.

### warning_not_bypassed

- **kind**: guard
- **description**: For request 1: The fix does not add an `opens` directive and does not suppress the warning.

### unexported_signature_left_alone

- **kind**: guard
- **description**: For request 2: The review does not ask to export `{{queue_class}}`, package
  `com.example.usage.internal`, or `ReportSpooler`, and does not ask to replace `{{queue_class}}` in the signature of
  `ReportSpooler.spool`.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects `rules/include/java/module-exports.md`
  before its first governed action.
