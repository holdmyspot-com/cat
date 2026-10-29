# Internal type in an exported signature

## Variables

- **queue_class**: The name of a public Java class in a non-exported package that stores usage reports in a file, such
	as `FileReportQueue`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

The project has two Java modules: `com.example.usage` in `usage/`, which exports `com.example.usage.api` and declares
the interface `ReportQueue` there, and `com.example.usage.test` in `usage-test/`. Package `com.example.usage.internal`
of module `com.example.usage.test`, which that module does not export, contains the public classes `{{queue_class}}`,
which implements `ReportQueue`, and `SigningKeys`, which holds the private keys that sign usage reports.

**Request 1.** The IDE reports `Class '{{queue_class}}' is not exported from module 'com.example.usage.test'` on
`UsageFixture` in `usage-test/src/main/java/com/example/usage/test/UsageFixture.java`. Tests in other modules call only
`drain()` and `size()` on the returned queue. Fix the warning and show the changed code.

**Request 2.** Review the new public class `ReportSpooler` in
`usage-test/src/main/java/com/example/usage/internal/ReportSpooler.java` before it is merged and report any module or
API concerns.

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
