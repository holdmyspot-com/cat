package io.github.cowwoc.cat.codex;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads one bounded JSON event from Codex's hook input stream.
 */
final class CodexHookInput
{
	private static final int MAXIMUM_INPUT_BYTES = 1_048_576;

	/**
	 * Reads an event object, treating empty input as an empty object for startup hooks.
	 *
	 * @param json mapper owned by the current hook invocation
	 * @param input native hook input
	 * @param eventName name used in an invalid-input diagnostic
	 * @return parsed event object
	 * @throws IOException if the input is too large or is not a JSON object
	 * @throws JacksonException if the input is malformed JSON
	 */
	static JsonNode read(JsonMapper json, InputStream input, String eventName) throws IOException
	{
		byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
		if (bytes.length > MAXIMUM_INPUT_BYTES)
			throw new IOException(eventName + " input is too large");
		JsonNode event = json.createObjectNode();
		if (bytes.length != 0)
			event = json.readTree(bytes);
		if (event == null || !event.isObject())
			throw new IOException(eventName + " input must be a JSON object");
		return event;
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexHookInput()
	{
	}
}
