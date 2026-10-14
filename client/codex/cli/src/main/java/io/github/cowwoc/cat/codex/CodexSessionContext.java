package io.github.cowwoc.cat.codex;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Owns the CAT rules file that one Codex session reads at its start.
 */
final class CodexSessionContext
{
	private static final String CONTEXT_DIRECTORY = "codex-context";
	private static final String FILE_EXTENSION = ".md";

	/**
	 * Prevents instantiation.
	 */
	private CodexSessionContext()
	{
	}

	/**
	 * Writes the rules for the Codex session that a SessionStart event starts.
	 *
	 * @param catTemp CAT's temporary root
	 * @param event the harness SessionStart event
	 * @param rules the session's rules
	 * @return the written rules file
	 * @throws IOException if the event lacks a valid session identifier or the file cannot be written
	 */
	static Path write(Path catTemp, JsonNode event, String rules) throws IOException
	{
		assert catTemp != null;
		assert event != null;
		assert rules != null;
		Path directory = Files.createDirectories(catTemp.resolve(CONTEXT_DIRECTORY));
		Path context = directory.resolve(sessionId(event) + FILE_EXTENSION);
		Files.writeString(context, rules, StandardCharsets.UTF_8);
		return context;
	}

	/**
	 * Returns a validated Codex harness session identifier.
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
			throw new IOException("Codex hook event is missing session_id");
		try
		{
			return UUID.fromString(value.stringValue()).toString();
		}
		catch (IllegalArgumentException e)
		{
			throw new IOException("Codex hook event has an invalid session_id: " + value.stringValue(), e);
		}
	}
}
