package com.holdmyspot.cat.codex;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.codex.rollout.RolloutLineage;
import com.holdmyspot.cat.common.JvmScope;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Pause;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.CommandLineArguments;
import com.holdmyspot.cat.common.history.SessionHistory;
import com.holdmyspot.cat.codex.secrets.CodexGetHistoryAccess;
import com.holdmyspot.cat.codex.secrets.CodexGetHistorySecrets;
import java.io.IOException;
import java.io.InputStream;
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
	 * The largest message text that {@code request-status} reads, in bytes.
	 */
	private static final int MAXIMUM_TEXT_BYTES = 1 << 20;

	static
	{
		CodexGetHistorySecrets.setCodexGetHistoryAccess(new CodexGetHistoryAccess()
		{
			@Override
			public String execute(JvmScope scope, Map<String, String> environment, String[] args, InputStream input)
				throws IOException
			{
				return CodexGetHistory.execute(scope, environment, args, input);
			}
		});
	}

	/**
 * Runs the requested session-history operation.
	 *
	 * @param args a thread identifier followed by an operation and its arguments
	 */
	public static void main(String[] args)
	{
		args = CommandLineArguments.recover(args);
		Map<String, String> environment = Platform.current().environment(System.getenv());
		try (JvmScope scope = new MainJvmScope(environment, Pause::sleeping))
		{
			System.out.print(execute(scope, environment, args, System.in));
		}
		catch (IllegalArgumentException | IOException e)
		{
			System.err.println(e.getMessage());
			System.exit(1);
		}
	}

	/**
	 * Runs the requested operation for an explicit Codex thread.
	 * <p>
	 * The {@code request-status} operation reads a user message's text, or its start, from {@code input} and reports
	 * whether a later turn answered it: the last matching message before the thread's latest compaction, which the
	 * compaction may have kept, and each matching message after it, which is a new request. The text arrives on
	 * standard input because a Windows batch launcher would alter it as an argument.
	 * <p>
	 * A thread that was forked from another has a rollout that holds only what the fork recorded itself. Every
	 * operation reads the records of the rollouts that the fork inherited its history from, up to where it inherited
	 * them, before the fork's own. A rollout that cannot be found or read leaves out its records and what it inherited
	 * without failing the operation.
	 *
	 * @param scope the process scope
	 * @param environment supplies {@code CODEX_HOME}, the directory that contains Codex rollouts, or {@code HOME},
	 *   whose {@code .codex} subdirectory is used when {@code CODEX_HOME} is unset or blank
	 * @param args a UUID thread identifier followed by a session-history operation and its arguments
	 * @param input the message text that the {@code request-status} operation reads
	 * @return the selected transcript and operation result
	 * @throws IllegalArgumentException if {@code args} does not start with a UUID thread identifier and an operation,
	 *   or {@code request-status} has arguments or reads blank text
	 * @throws IOException if the requested transcript cannot be resolved or read
	 */
	static String execute(JvmScope scope, Map<String, String> environment, String[] args, InputStream input)
		throws IOException
	{
		if (args.length < 2)
			throw new IllegalArgumentException(usage());

		UUID threadId = threadId(args[0]);
		Path transcript = transcript(environment, threadId);
		String[] operationArguments = Arrays.copyOfRange(args, 1, args.length);

		String result;
		if (operationArguments[0].equals("request-status"))
			result = requestStatus(scope, transcript, operationArguments, input);
		else
			result = SessionHistory.execute(scope, transcript, file -> RolloutLineage.lines(scope.json(), file),
				operationArguments);
		return "transcript=" + transcript + "\n" + result;
	}

	/**
	 * Reports whether a later turn answered a user message.
	 *
	 * @param scope the process scope
	 * @param transcript the thread's rollout
	 * @param operationArguments {@code request-status} alone
	 * @param input the message text, or its start
	 * @return the report
	 * @throws IllegalArgumentException if the operation has arguments or the text is blank
	 * @throws IOException if the rollout or the text cannot be read
	 */
	private static String requestStatus(JvmScope scope, Path transcript, String[] operationArguments,
		InputStream input) throws IOException
	{
		// WORKAROUND: https://github.com/openai/codex/issues/42611
		if (operationArguments.length != 1)
			throw new IllegalArgumentException(usage());
		String text = BoundedInput.readUtf8(input, "standard input", MAXIMUM_TEXT_BYTES, "message text").strip();
		if (text.isEmpty())
			throw new IllegalArgumentException("request-status reads the message text from standard input; it was " +
				"empty.\n" + usage());
		return RolloutRequestStatus.read(scope.json(), transcript).status(text);
	}

	/**
	 * Resolves the one rollout transcript matching a requested Codex thread identifier.
	 *
	 * @param environment supplies the variables that locate the Codex home that contains the rollouts, as Codex reads
	 *   them: {@code CODEX_HOME}, or the user's home directory, whose {@code .codex} subdirectory is used when
	 *   {@code CODEX_HOME} is unset or empty
	 * @param threadId the requested Codex thread identifier
	 * @return the normalized transcript path
	 * @throws IOException if Codex's home cannot be located as Codex locates it, or one unique transcript is
	 *   unavailable
	 */
	static Path transcript(Map<String, String> environment, UUID threadId) throws IOException
	{
		Path sessions = CodexInstallation.resolveHome(environment).resolve("sessions");
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
	 * Returns the supported Codex command forms.
	 *
	 * @return the command usage text
	 */
	private static String usage()
	{
		return "Usage: get-history <thread-id> analyze\n" +
			"       get-history <thread-id> search <phrase> [--context N]\n" +
			"       get-history <thread-id> errors\n" +
			"       get-history <thread-id> file-history <path-fragment>\n" +
			"       get-history <thread-id> request-status < <file holding the message text>";
	}

	/**
	 * Prevents instantiation.
	 */
	private CodexGetHistory()
	{
	}
}
