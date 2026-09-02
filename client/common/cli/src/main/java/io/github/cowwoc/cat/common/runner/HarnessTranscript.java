package io.github.cowwoc.cat.common.runner;

import io.github.cowwoc.cat.common.BoundedJsonLines;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads the harness-neutral retained transcript contract.
 */
public final class HarnessTranscript
{
	private static final int MAXIMUM_EVENT_CHARACTERS = 4 * 1024 * 1024;
	private static final Set<String> COMPLETED_TURN_FIELDS = Set.of("type", "runId", "exitCode", "error",
		"assistant_response");

	/**
	 * Returns the exact final response from a transcript containing one completed turn.
	 *
	 * @param json the operation-owned JSON mapper
	 * @param transcript the retained bridge transcript
	 * @return the nonblank assistant response
	 * @throws IOException if the transcript is malformed, ambiguous, unsuccessful, or missing the response
	 */
	public static String finalAssistantResponse(JsonMapper json, Path transcript) throws IOException
	{
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(transcript, "transcript must not be null");
		JsonMapper strict = json.rebuild().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
			DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).build();
		String[] response = new String[1];
		int[] completedTurns = new int[1];
		BoundedJsonLines.read(transcript, MAXIMUM_EVENT_CHARACTERS, "Harness transcript", (record, lineNumber) ->
		{
			ObjectNode event = event(strict, record, transcript, lineNumber);
			if (!event.path("type").asString().equals("turn-completed"))
				return;
			++completedTurns[0];
			if (!new HashSet<>(event.propertyNames()).equals(COMPLETED_TURN_FIELDS))
				throw new IOException("Harness turn-completed event has unsupported or missing fields: " + transcript +
					" (line " + lineNumber + ")");
			if (event.path("exitCode").asInt(-1) != 0 || !event.path("error").asString().isEmpty())
				throw new IOException("Harness turn-completed event reports failure: " + transcript + " (line " +
					lineNumber + ")");
			JsonNode assistantResponse = event.path("assistant_response");
			if (!assistantResponse.isString() || assistantResponse.asString().isBlank())
				throw new IOException("Harness turn-completed event has no assistant response: " + transcript +
					" (line " + lineNumber + ")");
			response[0] = assistantResponse.asString();
		});
		if (completedTurns[0] != 1)
			throw new IOException("Harness transcript must contain exactly one completed turn: " + transcript);
		return response[0];
	}

	/**
	 * Parses one transcript line as a JSON object.
	 *
	 * @param json the strict mapper
	 * @param record the line content
	 * @param transcript the source path
	 * @param lineNumber the source line number
	 * @return the parsed event
	 * @throws IOException if the event is not one JSON object with a type
	 */
	private static ObjectNode event(JsonMapper json, String record, Path transcript, int lineNumber) throws IOException
	{
		try
		{
			JsonNode parsed = json.readTree(record);
			if (!(parsed instanceof ObjectNode event) || !event.path("type").isString() ||
				event.path("type").asString().isBlank())
			{
				throw new IOException("Harness transcript event is not a typed JSON object: " + transcript + " (line " +
					lineNumber + ")");
			}
			return event;
		}
		catch (JacksonException e)
		{
			throw new IOException("Harness transcript contains invalid JSON: " + transcript + " (line " + lineNumber +
				")", e);
		}
	}

	/**
	 * Prevents construction of this static transcript reader.
	 */
	private HarnessTranscript()
	{
	}
}
