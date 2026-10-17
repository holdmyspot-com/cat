# Each site documents its goal

## Design Goals

- Prove that each documentation site answers its own reader's question: a field states what its value means, a
  method that checks its input documents the check, code that only stores, sums or iterates over the values leaves the
  check out, and a factory that skips the check still tells its caller what it must supply.

## Variables

- **report_class**: The name of a class that reports one turn's usage, such as `TurnReport` or `TurnUsage`.

## Scenario

Add Javadoc to this class and to every field, constructor and method in it, since none has any yet. Show the whole
class.

```java
public final class {{report_class}}
{
	private final List<Request> requests;
	private final long outputTokens;

	private {{report_class}}(List<Request> requests)
	{
		this.requests = requests;
		long sum = 0;
		for (Request request : requests)
			sum += request.outputTokens();
		this.outputTokens = sum;
	}

	public static {{report_class}} of(List<Request> requests)
	{
		for (int i = 0; i < requests.size(); ++i)
		{
			if (requests.get(i).index() != i)
				throw new IllegalArgumentException("Request " + i + " is out of order");
		}
		return new {{report_class}}(List.copyOf(requests));
	}

	public static {{report_class}} ofOrdered(List<Request> requests)
	{
		return new {{report_class}}(List.copyOf(requests));
	}

	public long outputTokens()
	{
		return outputTokens;
	}

	public void forEachRequest(Consumer<Request> action)
	{
		requests.forEach(action);
	}
}
```

`Request` is a record of one model request in a turn: its index in the turn, starting at zero, and the output tokens
that the model returned for it. `ofOrdered` exists for callers that read requests from a log that the harness already
writes in index order.

## Assertions

### other_sites_leave_check_out

- **kind**: effect
- **description**: The Javadoc of the `requests` and `outputTokens` fields, the constructor, `outputTokens()` and
  `forEachRequest()` does not mention the index check, whether as a qualifier such as “verified,” “validated,”
  “checked” or “in-order” or as a clause about how the requests were checked.

### field_states_meaning

- **kind**: effect
- **description**: The Javadoc of the `outputTokens` field states that it holds the turn's output tokens summed over
  its requests.

### check_documented_where_performed

- **kind**: guard
- **description**: The Javadoc of `of` states that it checks that the request indices run consecutively from zero, and
  that it throws `IllegalArgumentException` when they do not.

### caller_distinction_kept

- **kind**: guard
- **description**: The Javadoc of `ofOrdered` states that it does not check the request indices, so the caller must
  supply requests whose indices run consecutively from zero.
