# Quantity scope named

## Design Goals

- Prove that the description of a quantity that combines several values names the scope that bounds it beside the
	quantity, and leaves out how the code avoids counting a value twice.

## Variables

- **quantity_field**: The name of a `long` field that accumulates output tokens, such as `outputTokens` or
	`generatedTokens`.

## Scenario

The project contains this class:

```java
public final class TurnReader
{
	private final List<JsonNode> events;
	private final int selectedTurn;
	private final Set<String> countedMessageIds = new HashSet<>();
	private long {{quantity_field}};

	public TurnReader(List<JsonNode> events, int selectedTurn)
	{
		this.events = events;
		this.selectedTurn = selectedTurn;
		for (JsonNode event : events)
			count(event);
	}

	private void count(JsonNode event)
	{
		if (event.path("turn").asInt() != selectedTurn)
			return;
		String messageId = event.path("message_id").asText();
		if (!countedMessageIds.add(messageId))
			return;
		{{quantity_field}} += event.path("usage").path("output_tokens").asLong();
	}
}
```

`events` holds every event of a session, across all of its turns. The harness can write the usage of one response in
several events that share the response's message ID.

Write the Javadoc for the `{{quantity_field}}` field. Show only the field and its Javadoc.

## Assertions

### scope_beside_quantity

- **kind**: effect
- **description**: The Javadoc says that `{{quantity_field}}` holds the output tokens of the selected turn, summed over
	that turn's responses, and places the selected turn beside the quantity so that it cannot be read as a total across
	the session.

### duplicate_handling_left_out

- **kind**: effect
- **description**: The Javadoc does not describe how repeated usage events or message IDs are skipped, such as by
	saying that a response counts once or that duplicates are ignored.

### quantity_identified

- **kind**: guard
- **description**: The Javadoc identifies the quantity as output tokens, not as all tokens or as input tokens.
