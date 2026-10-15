package io.github.cowwoc.cat.claude;

import io.github.cowwoc.cat.common.CommandLineArguments;
import io.github.cowwoc.cat.common.history.SessionHistory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Runs a session-history operation against an explicitly requested Claude Code session or subagent.
 */
public final class ClaudeGetHistory
{
	/**
 * Runs the requested session-history operation.
	 *
	 * @param args a session identifier, optional agent identifier, and operation arguments
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		try
		{
			System.out.print(execute(System.getenv(), args));
		}
		catch (IllegalArgumentException | IOException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Runs the requested operation for an explicit Claude Code session or subagent.
	 *
	 * @param environment supplies {@code CLAUDE_CONFIG_DIR}, the directory that contains Claude Code projects, or
	 *   {@code HOME}, whose {@code .claude} subdirectory is used when {@code CLAUDE_CONFIG_DIR} is unset or blank
	 * @param args a UUID session identifier, optional agent identifier, and operation arguments
	 * @return the selected transcript and operation result
	 * @throws IllegalArgumentException if {@code args} does not start with a UUID session identifier and an operation
	 * @throws IOException if the requested transcript cannot be resolved or read
	 */
	public static String execute(Map<String, String> environment, String[] args) throws IOException
	{
		if (args.length < 2)
			throw new IllegalArgumentException(usage());

		UUID sessionId = sessionId(args[0]);
		int operationStart = 1;
		Path transcript;
		if ("--agent".equals(args[operationStart]))
		{
			if (args.length < 4)
				throw new IllegalArgumentException(usage());
			String agentId = agentId(args[operationStart + 1]);
			transcript = agentTranscript(environment, sessionId, agentId);
			operationStart = 3;
		}
		else
			transcript = transcript(environment, sessionId);

		String[] operationArguments = Arrays.copyOfRange(args, operationStart, args.length);
		return "transcript=" + transcript + "\n" + SessionHistory.execute(transcript, operationArguments);
	}

	/**
	 * Resolves the main transcript for a requested Claude Code session.
	 *
	 * @param environment supplies {@code CLAUDE_CONFIG_DIR}, the directory that contains Claude Code projects, or
	 *   {@code HOME}, whose {@code .claude} subdirectory is used when {@code CLAUDE_CONFIG_DIR} is unset or blank
	 * @param sessionId the requested Claude Code session identifier
	 * @return the normalized transcript path
	 * @throws IOException if the required Claude configuration directory or one unique transcript is unavailable
	 */
	static Path transcript(Map<String, String> environment, UUID sessionId) throws IOException
	{
		Path projects = configurationDirectory(environment).toAbsolutePath().normalize().resolve("projects");
		if (!Files.isDirectory(projects))
			throw new IOException("Claude transcript directory does not exist: " + projects);
		List<Path> matches = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(projects))
		{
			paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals(sessionId + ".jsonl")).
				forEach(matches::add);
		}
		if (matches.size() != 1)
			throw new IOException("Expected exactly one Claude transcript for session ID " + sessionId +
				" beneath " + projects + "; found " + matches.size());
		return matches.getFirst().toAbsolutePath().normalize();
	}

	/**
	 * Resolves a requested Claude Code subagent transcript within a session.
	 *
	 * @param environment supplies {@code CLAUDE_CONFIG_DIR}, the directory that contains Claude Code projects, or
	 *   {@code HOME}, whose {@code .claude} subdirectory is used when {@code CLAUDE_CONFIG_DIR} is unset or blank
	 * @param sessionId the requested Claude Code session identifier
	 * @param agentId the requested agent identifier
	 * @return the normalized subagent transcript path
	 * @throws IOException if the session or one unique subagent transcript is unavailable
	 */
	private static Path agentTranscript(Map<String, String> environment, UUID sessionId, String agentId)
		throws IOException
	{
		Path mainTranscript = transcript(environment, sessionId);
		Path sessionDirectory = mainTranscript.resolveSibling(sessionId.toString());
		if (!Files.isDirectory(sessionDirectory))
			throw new IOException("Claude subagent transcript directory does not exist: " + sessionDirectory);

		List<Path> matches = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(sessionDirectory))
		{
			paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals("agent-" + agentId +
				".jsonl")).forEach(matches::add);
		}
		if (matches.size() != 1)
			throw new IOException("Expected exactly one Claude subagent transcript for session ID " + sessionId +
				" and agent ID " + agentId + " beneath " + sessionDirectory + "; found " + matches.size());
		return matches.getFirst().toAbsolutePath().normalize();
	}

	/**
	 * Parses a Claude Code session identifier supplied to the command.
	 *
	 * @param value the requested session identifier
	 * @return the parsed identifier
	 * @throws IllegalArgumentException if {@code value} is not a UUID
	 */
	private static UUID sessionId(String value)
	{
		try
		{
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("Session ID must be a UUID: " + value, e);
		}
	}

	/**
	 * Validates a Claude Code subagent identifier supplied to the command.
	 *
	 * @param value the requested subagent identifier
	 * @return the validated identifier
	 * @throws IllegalArgumentException if {@code value} contains a character other than a letter, digit, or hyphen
	 */
	private static String agentId(String value)
	{
		if (!value.matches("[A-Za-z0-9-]+"))
			throw new IllegalArgumentException("Agent ID must contain only letters, digits, and hyphens: " + value);
		return value;
	}

	/**
	 * Returns the Claude Code configuration directory that holds the transcripts.
	 *
	 * @param environment the caller's environment
	 * @return {@code CLAUDE_CONFIG_DIR} when it is set and non-blank, otherwise {@code .claude} below {@code HOME}
	 * @throws IOException if {@code CLAUDE_CONFIG_DIR} and {@code HOME} are both unset or blank
	 */
	private static Path configurationDirectory(Map<String, String> environment) throws IOException
	{
		String configured = environment.get("CLAUDE_CONFIG_DIR");
		String home = environment.get("HOME");
		if (home != null && !home.isBlank())
			return ClaudeConfiguration.resolveDirectory(Path.of(home.strip()), configured);
		if (configured == null || configured.isBlank())
		{
			throw new IOException("Neither CLAUDE_CONFIG_DIR nor HOME is set, so the Claude Code configuration " +
				"directory that holds the transcripts cannot be located. Set HOME, or set CLAUDE_CONFIG_DIR to the Claude " +
				"Code configuration directory.");
		}
		// Preserve this command's path trimming when it receives only an explicit configuration directory.
		return ClaudeConfiguration.resolveDirectory(null, configured.strip());
	}

	/**
	 * Returns the supported Claude Code command forms.
	 *
	 * @return the command usage text
	 */
	private static String usage()
	{
		return "Usage: get-history <session-id> analyze\n" +
			"       get-history <session-id> search <phrase> [--context N]\n" +
			"       get-history <session-id> errors\n" +
			"       get-history <session-id> file-history <path-fragment>\n" +
			"       get-history <session-id> --agent <agent-id> <operation> [arguments]";
	}

	/**
	 * Prevents instantiation.
	 */
	private ClaudeGetHistory()
	{
	}
}
