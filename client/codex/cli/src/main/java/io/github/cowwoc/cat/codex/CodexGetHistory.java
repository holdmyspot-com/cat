package io.github.cowwoc.cat.codex;

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
 * Runs a session-history operation against an explicitly requested Codex thread.
 */
public final class CodexGetHistory
{
	/**
 * Runs the requested session-history operation.
	 *
	 * @param args a thread identifier followed by an operation and its arguments
	 */
	public static void main(String[] args)
	{
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
	 * Runs the requested operation for an explicit Codex thread.
	 *
	 * @param environment supplies {@code CODEX_HOME}, the directory that contains Codex rollouts
	 * @param args a UUID thread identifier followed by a session-history operation and its arguments
	 * @return the selected transcript and operation result
	 * @throws IllegalArgumentException if {@code args} does not start with a UUID thread identifier and an operation
	 * @throws IOException if the requested transcript cannot be resolved or read
	 */
	static String execute(Map<String, String> environment, String[] args) throws IOException
	{
		if (args.length < 2)
			throw new IllegalArgumentException(usage());

		UUID threadId = threadId(args[0]);
		Path transcript = transcript(environment, threadId);
		String[] operationArguments = Arrays.copyOfRange(args, 1, args.length);

		return "transcript=" + transcript + "\n" + SessionHistory.execute(transcript, operationArguments);
	}

	/**
	 * Resolves the one rollout transcript matching a requested Codex thread identifier.
	 *
	 * @param environment supplies {@code CODEX_HOME}, the directory that contains Codex rollouts
	 * @param threadId the requested Codex thread identifier
	 * @return the normalized transcript path
	 * @throws IOException if the required Codex home or one unique transcript is unavailable
	 */
	static Path transcript(Map<String, String> environment, UUID threadId) throws IOException
	{
		String codexHome = required(environment, "CODEX_HOME");
		Path sessions = Path.of(codexHome).toAbsolutePath().normalize().resolve("sessions");
		if (!Files.isDirectory(sessions))
			throw new IOException("Codex transcript directory does not exist: " + sessions);
		List<Path> matches = new ArrayList<>();
		try (Stream<Path> paths = Files.walk(sessions))
		{
			paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith("-" + threadId +
				".jsonl")).forEach(matches::add);
		}
		if (matches.size() != 1)
			throw new IOException("Expected exactly one Codex rollout for thread ID " + threadId + " beneath " +
				sessions + "; found " + matches.size());
		return matches.getFirst().toAbsolutePath().normalize();
	}

	/**
	 * Parses a Codex thread identifier supplied to the command.
	 *
	 * @param value the requested thread identifier
	 * @return the parsed identifier
	 * @throws IllegalArgumentException if {@code value} is not a UUID
	 */
	private static UUID threadId(String value)
	{
		try
		{
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalArgumentException("Thread ID must be a UUID: " + value, e);
		}
	}

	/**
	 * Returns one required harness environment value.
	 *
	 * @param environment the Codex harness environment
	 * @param name the value name
	 * @return the non-blank value
	 * @throws IOException if the value is absent
	 */
	private static String required(Map<String, String> environment, String name) throws IOException
	{
		String value = environment.get(name);
		if (value == null || value.isBlank())
			throw new IOException(name + " is required to locate the current Codex transcript");
		return value.strip();
	}

	/**
	 * Returns the supported Codex command forms.
	 *
	 * @return the command usage text
	 */
	private static String usage()
	{
		return "Usage: get-history <thread-id> analyze\n" +
			"       get-history <thread-id> search <phrase> [--context N]\n" +
			"       get-history <thread-id> errors\n" +
			"       get-history <thread-id> file-history <path-fragment>";
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexGetHistory()
	{
	}
}
