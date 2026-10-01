package io.github.cowwoc.cat.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Owns the CAT rule-context files that must remain available for one Claude session.
 */
final class ClaudeSessionContext
{
	private static final String CONTEXT_DIRECTORY = "claude-context";
	private static final String FILE_EXTENSION = ".md";

	/**
	 * Prevents instantiation.
	 */
	private ClaudeSessionContext()
	{
	}

	/**
	 * Writes the complete rules for one Claude harness event.
	 *
	 * @param catTemp CAT's temporary root
	 * @param event the harness hook event
	 * @param eventName the event name
	 * @param rules the complete rules
	 * @return the written context file
	 * @throws IOException if the session identifier is invalid or the context cannot be written
	 */
	static Path write(Path catTemp, JsonNode event, String eventName, String rules) throws IOException
	{
		assert catTemp != null;
		assert event != null;
		assert eventName != null;
		assert rules != null;
		Path directory = contextDirectory(catTemp);
		Files.createDirectories(directory);
		Path context = directory.resolve(fileName(event, eventName));
		Files.writeString(context, rules, StandardCharsets.UTF_8);
		return context;
	}

	/**
	 * Deletes the context files owned by a completed Claude session.
	 *
	 * @param catTemp CAT's temporary root
	 * @param event the harness SessionEnd event
	 * @throws IOException if the session identifier is invalid or a context file cannot be deleted
	 */
	static void delete(Path catTemp, JsonNode event) throws IOException
	{
		assert catTemp != null;
		assert event != null;
		Path directory = contextDirectory(catTemp);
		if (!Files.isDirectory(directory))
			return;
		String sessionId = sessionId(event);
		Files.deleteIfExists(directory.resolve(sessionId + FILE_EXTENSION));
		try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, sessionId + "-*" + FILE_EXTENSION))
		{
			for (Path file: files)
				Files.deleteIfExists(file);
		}
		try
		{
			Files.deleteIfExists(directory);
		}
		catch (DirectoryNotEmptyException _)
		{
			// Delete only CAT-owned names; another owner may have added a file to this directory.
		}
	}

	/**
	 * Returns the CAT-owned context directory.
	 *
	 * @param catTemp CAT's temporary root
	 * @return the context directory
	 */
	private static Path contextDirectory(Path catTemp)
	{
		assert catTemp != null;
		return catTemp.resolve(CONTEXT_DIRECTORY);
	}

	/**
	 * Returns a validated Claude harness session identifier.
	 *
	 * @param event the harness hook event
	 * @return the session identifier
	 * @throws IOException if the event lacks a UUID session identifier
	 */
	private static String sessionId(JsonNode event) throws IOException
	{
		assert event != null;
		JsonNode value = event.path("session_id");
		if (!value.isString())
			throw new IOException("Claude hook event is missing session_id");
		try
		{
			return UUID.fromString(value.stringValue()).toString();
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Claude hook event has an invalid session_id", e);
		}
	}

	/**
	 * Returns the session-specific file name owned by a Claude context-producing event.
	 *
	 * @param event the harness hook event
	 * @param eventName the harness event name
	 * @return the file name for the event
	 */
	private static String fileName(JsonNode event, String eventName) throws IOException
	{
		assert event != null;
		assert eventName != null;
		return switch (eventName)
		{
			case "SessionStart" -> sessionId(event) + FILE_EXTENSION;
			case "SubagentStart" -> sessionId(event) + "-" + agentId(event) + FILE_EXTENSION;
			default -> throw new IllegalArgumentException("Unsupported Claude context event: " + eventName);
		};
	}

	/**
	 * Returns the identifier for a Claude harness subagent.
	 *
	 * @param event the harness SubagentStart event
	 * @return the subagent identifier
	 * @throws IOException if the identifier is absent or unsafe as a file name component
	 */
	private static String agentId(JsonNode event) throws IOException
	{
		assert event != null;
		JsonNode value = event.path("agent_id");
		if (!value.isString() || !value.stringValue().matches("[A-Za-z0-9-]+"))
			throw new IOException("Claude SubagentStart event has an invalid agent_id");
		return value.stringValue();
	}
}
