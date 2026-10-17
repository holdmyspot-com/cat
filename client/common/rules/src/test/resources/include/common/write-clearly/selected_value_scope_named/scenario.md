# Selected value scope named

## Design Goals

- Prove that the description of a value chosen from several values, such as the latest one, names the scope that bounds
  the choice beside the value.

## Variables

- **time_field**: The name of an `Instant` field that holds when a response finished, such as `lastResponseTime` or
  `finishedAt`

## Scenario

The project contains this class:

```java
public final class TurnReader
{
	private final int selectedTurn;
	private Instant {{time_field}};

	public TurnReader(List<JsonNode> events, int selectedTurn)
	{
		this.selectedTurn = selectedTurn;
		for (JsonNode event : events)
			read(event);
	}

	private void read(JsonNode event)
	{
		if (event.path("turn").asInt() != selectedTurn)
			return;
		Instant finished = Instant.parse(event.path("finished_at").asText());
		if ({{time_field}} == null || finished.isAfter({{time_field}}))
			{{time_field}} = finished;
	}
}
```

`events` holds every event of a session, across all of its turns.

Write the Javadoc for the `{{time_field}}` field. Show only the field and its Javadoc.

## Assertions

### scope_beside_selected_value

- **kind**: effect
- **description**: The Javadoc says that `{{time_field}}` holds when the latest response of the selected turn finished,
  and places the selected turn beside the latest response so that it cannot be read as the latest response of the
  session.

### selection_identified

- **kind**: guard
- **description**: The Javadoc identifies the value as the finish time of the latest response, not of the first
  response or of the turn's start.
