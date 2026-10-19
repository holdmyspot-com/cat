# Java registry fields

## Variables

- **registry_class**: The name of a Java class that records named entries for one owner, such as `PluginRegistry`

## Scenario

Handle each of the following independent requests. Answer them in order and label each answer with its request number.

**Request 1.** Write a Java class `{{registry_class}}` whose constructor takes the owner's name. It keeps the names that
callers register, in registration order, and has the methods `void register(String name)`, `List<String> names()`
and `String owner()`. Show the whole class.

**Request 2.** This class rejects a task once it holds `limit` tasks, but it accepts one task too many. Fix it and show
the whole class:

```java
public final class TaskQueue
{
	private final int limit;
	private final Deque<String> tasks;

	public TaskQueue(int limit)
	{
		if (limit < 1)
			throw new IllegalArgumentException("limit must be positive: " + limit);
		this.limit = limit;
		this.tasks = new ArrayDeque<>();
	}

	public boolean offer(String task)
	{
		if (tasks.size() > limit)
			return false;
		tasks.add(task);
		return true;
	}
}
```

## Assertions

### collection_initialized_at_declaration

- **kind**: effect
- **description**: For request 1: The field that holds the registered names receives its empty collection in its
	declaration, not through an assignment in the constructor or an instance initializer block.

### parameter_field_assigned_in_constructor

- **kind**: guard
- **description**: For request 1: The field that holds the owner's name is assigned from the constructor parameter in
	the constructor.

### validated_field_stays_in_constructor

- **kind**: guard
- **description**: For request 2: `limit` is still assigned in the constructor after its validation, and the class
	accepts exactly `limit` tasks; any other change to the constructor leaves each new queue's state unchanged.

### guidance_selection

- **kind**: effect
- **description**: The retained events establish that the executor selects
	`rules/include/common/field-initialization.md` before its first governed action.
