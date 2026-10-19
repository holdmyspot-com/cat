# Java Code Flow

## Design Goals

- Let a code-flow review of a Java change find the entry points that start each use case and resolve every call that
	Java dispatches indirectly to the code that actually runs.
- Classify changed Java code that does not appear in a call as executable or non-executable by whether it changes
	runtime behavior.

## Guidance

### Entry points

Look for these entry points when tracing callers upward:

- A `public static void main(String[])` method, and the dispatcher that maps its arguments to a command handler.
- A method that a framework or container calls: an annotated handler such as `@Path` with `@GET`, `@RequestMapping`, or
	`@Test`; a servlet's `doGet` or `doPost`; a listener or callback interface method.
- A `Runnable` or `Callable` submitted to an executor or scheduler.
- A service implementation loaded with `ServiceLoader`, named by a `provides` clause in `module-info.java` or by a
	`META-INF/services` file.
- For a library, the public methods of the packages that `module-info.java` exports.

### Indirect calls

- **Interface, abstract, or overridden method**: find where this use case creates the receiver, such as a constructor
	call, a factory method, or a dependency-injection binding, and follow that class's implementation.
- **Lambda or method reference**: its body runs where the functional interface's method is called, not where the lambda
	is written. Place the step at the call and link both locations.
- **Stream pipeline**: intermediate operations such as `map` and `filter` run only when the terminal operation runs, one
	element at a time. Place their lambda steps at the terminal operation.
- **Implicit code**: a constructor, field initializer, or instance initializer runs at `new`; a `static` initializer runs
	when the class is first used. Include them as steps when they are changed.
- **Exception**: a `throw` continues at the nearest matching `catch` up the call stack. Show that `catch` as the next
	step when it is changed, and show a `finally` block or a try-with-resources `close()` method when it runs changed code.
- **Reflection, `ServiceLoader`, or a runtime-processed annotation**: name the code that performs the lookup and the
	class or member it selects.
- **Another thread**: a task submitted to an executor or a started `Thread` runs later and concurrently. Mark the step as
	asynchronous and state what, if anything, the caller waits for.

### Changes outside a call

- Treat imports, Javadoc and comments, formatting, and annotations that only tools read, such as `@Override` and
	`@SuppressWarnings`, as non-executable.
- Treat an annotation that runtime code reads, such as a request-mapping or JSON-mapping annotation, as executable, and
	show it at the step whose behavior it changes.
- A `module-info.java` change alters which packages and services are visible. Show it at the step that depends on it.
- Test methods live under `src/test/java`; attach each to the use case whose entry point or steps it calls.
