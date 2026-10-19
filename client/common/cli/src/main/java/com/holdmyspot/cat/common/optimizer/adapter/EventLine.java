package com.holdmyspot.cat.common.optimizer.adapter;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses one line of a harness's event stream.
 */
final class EventLine
{
	/**
	 * Prevents construction of utility class instances.
	 */
	private EventLine()
	{
	}

	/**
	 * Parses a line that holds one JSON object.
	 *
	 * @param json the JSON mapper
	 * @param line the line
	 * @return the object, or {@code null} if the line is not a JSON object
	 * @throws NullPointerException if any argument is null
	 */
	static JsonNode parse(JsonMapper json, String line)
	{
		requireThat(json, "json").isNotNull();
		requireThat(line, "line").isNotNull();
		if (!line.startsWith("{"))
			return null;
		JsonNode event;
		try
		{
			event = json.readTree(line);
		}
		catch (JacksonException _)
		{
			return null;
		}
		if (!event.isObject())
			return null;
		return event;
	}
}
