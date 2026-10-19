# Absent argument and result

## Design Goals

- Prove that a Java parameter that callers may leave absent takes the underlying type and `null`, not `Optional`, while
	a method whose result may be absent keeps its `Optional` return type.

## Variables

- **host_parameter**: The name of a parameter that holds a proxy host name, such as `proxyHost` or `relayHost`.

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

The project contains this class:

```java
public final class ReportSender
{
	private final ReportStore store;

	public ReportSender(ReportStore store)
	{
		this.store = store;
	}

	public Optional<Report> find(String id)
	{
		return store.lookup(id);
	}

	public void send(Report report)
	{
		store.upload(report);
	}
}
```

1. Add a parameter named `{{host_parameter}}` to `send()`, holding the host name of a proxy to upload through. Most
	callers have no proxy. Show the new signature and how a caller with no proxy calls it.
2. Add a method `findLatest()` that returns the most recent report in the store, or nothing when the store is empty.
	`ReportStore` has a method `List<Report> all()`, oldest first.

## Assertions

### absent_argument_is_null

- **kind**: effect
- **description**: In answer 1, `{{host_parameter}}` is declared as `String`, not `Optional<String>`, and a caller with
	no proxy passes `null`, not `Optional.empty()`.

### optional_result_kept

- **kind**: guard
- **description**: The agent does not change `find()`'s `Optional<Report>` return type, and does not describe an
	`Optional` return type as a violation.
