# Java Temporary Resources

## Design Goals

- Keep Java temporary-resource cleanup under the project's supported ownership lifecycle and keep test fixtures
	isolated.

## Guidance

When Java code creates one or more temporary directories whose final owner is the current operation, use the shared
`TemporaryDirectories` `AutoCloseable` scope in a try-with-resources header: create each directory directly and add it
to the scope immediately. This prevents an exception between creation and an open-coded cleanup block from leaving the
directory behind. Do not add another creation wrapper or cleanup registry. A directory handed to a later owner, such as
retained evidence, is different: record that handoff and use the lifecycle that removes it after its final consumer.

For a Java test, use that same scope for every temporary directory it creates. Do not use `@BeforeMethod` or
`@AfterMethod` to create or remove test fixtures.
