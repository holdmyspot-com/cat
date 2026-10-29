# Follow-up results

## Design Goals

- Prove that the Javadoc of a method's result states the condition of each value its caller must act on, whichever
	value that is: when only `true` needs a follow-up action, its condition is stated and `false` is named briefly;
	when each value needs a different follow-up action, both conditions are stated.

## Variables

- **resource**: What the lease guards, with its article, such as `a shared cache file` or `a scratch directory`.

## Scenario

Add Javadoc to this class and to every field, constructor and method in it, since none has any yet. Show the whole
class.

```java
public final class Lease
{
	private final Set<String> holders = new HashSet<>();
	private final int capacity;

	public Lease(int capacity)
	{
		this.capacity = capacity;
	}

	public synchronized boolean acquire(String holder)
	{
		if (holders.contains(holder))
			return true;
		if (holders.size() == capacity)
			return false;
		holders.add(holder);
		return true;
	}

	public synchronized boolean release(String holder)
	{
		holders.remove(holder);
		return holders.isEmpty();
	}
}
```

A `Lease` guards {{resource}} that several workers use at once. A worker calls `acquire` before using it and queues
its work for later when `acquire` returns `false`. The worker calls `release` when it is done, and deletes
{{resource}} when `release` returns `true`.

## Assertions

### last_release_condition

- **kind**: effect
- **description**: The `@return` of `release` states that `true` means no holder remains after this release, and does
	not describe the `true` case as a success.

### capacity_refusal_named

- **kind**: effect
- **description**: The `@return` of `acquire` names the case that makes it return `false` directly, as the lease
	already having its full number of holders, and states that `true` means the holder now holds the lease, including
	when it already held it.

### release_false_brief

- **kind**: guard
- **description**: The `@return` of `release` states the `false` case in a few words, such as “`false` otherwise” or
	“`false` if another holder remains”, without describing it as a failure.
