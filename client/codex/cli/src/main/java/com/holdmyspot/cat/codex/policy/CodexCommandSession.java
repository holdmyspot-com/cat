package com.holdmyspot.cat.codex.policy;

import com.holdmyspot.cat.codex.layout.CodexInstallation;
import com.holdmyspot.cat.common.runner.policy.PolicyRefusal;
import com.holdmyspot.cat.common.runner.policy.ProcessDescription;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The Codex session that runs a command, which a runner launcher identifies from sources that the session's commands
 * cannot change: the Codex processes among the launcher's ancestors, and the rollout of the thread that
 * {@value #THREAD_VARIABLE} names in Codex's home. The rollout's first record gives the thread's working directory,
 * which is the session's project folder, and the session that the thread belongs to, whose rollout is the session's
 * transcript. A thread of Codex's shared background server is identified the same way.
 *
 * @param session      the session
 * @param rollout      the absolute path of the rollout of the thread that runs the command
 * @param project      the session's absolute project folder
 * @param askingThread the thread that runs the command, or empty if it is the session's main thread
 */
public record CodexCommandSession(CodexSession session, Path rollout, Path project, Optional<String> askingThread)
{
	/**
	 * The variable in which Codex gives a command the identifier of the thread that runs it.
	 */
	public static final String THREAD_VARIABLE = "CODEX_THREAD_ID";
	/**
	 * The variable that names Codex's home.
	 */
	private static final String HOME_VARIABLE = "CODEX_HOME";
	/**
	 * Parses the first record of a rollout.
	 */
	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * Creates a session that runs a command.
	 *
	 * @param session      the session
	 * @param rollout      the absolute path of the rollout of the thread that runs the command
	 * @param project      the session's absolute project folder
	 * @param askingThread the thread that runs the command, or empty if it is the session's main thread
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code rollout} or {@code project} are not absolute
	 */
	public CodexCommandSession
	{
		requireThat(session, "session").isNotNull();
		requireThat(rollout, "rollout").isNotNull().isAbsolute();
		requireThat(project, "project").isNotNull().isAbsolute();
		requireThat(askingThread, "askingThread").isNotNull();
	}

	/**
	 * Finds the session that runs a command.
	 *
	 * @param environment the command's environment variables
	 * @param accountHome the user's home folder in the account database
	 * @param ancestors   the processes that run the command, from its parent up
	 * @return the session
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code accountHome} is not absolute
	 * @throws IOException              if Codex's home or a rollout cannot be read
	 * @throws PolicyRefusal            if the sources do not identify exactly one session: the environment names no
	 *                                  thread, Codex's home is not an absolute folder, no rollout or more than one
	 *                                  has the thread's or its session's identifier, or a rollout's first record
	 *                                  names no session or working directory
	 */
	public static CodexCommandSession find(Map<String, String> environment, Path accountHome,
		List<ProcessDescription> ancestors) throws IOException, PolicyRefusal
	{
		requireThat(environment, "environment").isNotNull();
		requireThat(accountHome, "accountHome").isNotNull().isAbsolute();
		requireThat(ancestors, "ancestors").isNotNull();
		String threadId = environment.get(THREAD_VARIABLE);
		if (threadId == null || !CodexPolicyReader.THREAD_ID.matcher(threadId).matches())
		{
			throw new PolicyRefusal("the command's environment does not name its Codex thread in " +
				THREAD_VARIABLE, "Run the launcher from a Codex session");
		}
		String configured = environment.get(HOME_VARIABLE);
		if (configured != null && !configured.isEmpty() && !isAbsolute(configured))
		{
			throw new PolicyRefusal("the command's " + HOME_VARIABLE + " \"" + configured + "\" is not an absolute " +
				"path", "Set " + HOME_VARIABLE + " to an absolute path, then start a new Codex session");
		}
		Path codexHome = CodexInstallation.resolveHome(environment, accountHome, accountHome);
		Path rollout = rollout(codexHome, threadId);
		Meta thread = meta(rollout);
		Path transcript = rollout;
		Meta main = thread;
		Optional<String> askingThread = Optional.empty();
		if (!thread.sessionId().equalsIgnoreCase(threadId))
		{
			askingThread = Optional.of(threadId);
			transcript = rollout(codexHome, thread.sessionId());
			main = meta(transcript);
		}
		CodexSession session = new CodexSession(ancestors, environment, accountHome, main.cwd(),
			Optional.of(transcript), thread.sessionId());
		return new CodexCommandSession(session, rollout, thread.cwd(), askingThread);
	}

	/**
	 * Indicates whether a path is absolute.
	 *
	 * @param path the path
	 * @return {@code true} if it is an absolute path
	 */
	private static boolean isAbsolute(String path)
	{
		try
		{
			return Path.of(path).isAbsolute();
		}
		catch (InvalidPathException _)
		{
			return false;
		}
	}

	/**
	 * Returns the one rollout of a thread.
	 *
	 * @param codexHome Codex's home
	 * @param threadId  the thread's identifier
	 * @return the rollout's absolute path
	 * @throws IOException   if the rollouts cannot be listed
	 * @throws PolicyRefusal if no rollout or more than one has the thread's identifier
	 */
	private static Path rollout(Path codexHome, String threadId) throws IOException, PolicyRefusal
	{
		Path sessions = codexHome.resolve("sessions");
		String suffix = "-" + threadId + ".jsonl";
		List<Path> rollouts = List.of();
		if (Files.isDirectory(sessions, LinkOption.NOFOLLOW_LINKS))
		{
			try (Stream<Path> files = Files.walk(sessions))
			{
				rollouts = files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).
					filter(file ->
					{
						String name = file.getFileName().toString();
						return name.startsWith("rollout-") && name.endsWith(suffix);
					}).map(file -> file.toAbsolutePath().normalize()).sorted().toList();
			}
		}
		if (rollouts.isEmpty())
		{
			throw new PolicyRefusal("no rollout of the Codex thread " + threadId + " is in " + sessions,
				"Run the launcher from a Codex session that keeps its rollout");
		}
		if (rollouts.size() > 1)
		{
			throw new PolicyRefusal("more than one rollout of the Codex thread " + threadId + " is in " + sessions +
				": " + rollouts, "Remove the rollouts that do not belong to the thread, then start a new Codex " +
				"session");
		}
		return rollouts.getFirst();
	}

	/**
	 * Reads what a rollout's first record says about its thread.
	 *
	 * @param rollout the rollout
	 * @return the thread's session and working directory
	 * @throws IOException   if the rollout cannot be read
	 * @throws PolicyRefusal if its first record is not a {@code session_meta} record that names the thread's session
	 *                       and an absolute working directory
	 */
	private static Meta meta(Path rollout) throws IOException, PolicyRefusal
	{
		String line;
		try (BufferedReader reader = Files.newBufferedReader(rollout, StandardCharsets.UTF_8))
		{
			line = reader.readLine();
		}
		if (line != null)
		{
			try
			{
				JsonNode record = JSON.readTree(line);
				JsonNode payload = record.path("payload");
				JsonNode sessionId = payload.path("session_id");
				if (!sessionId.isString())
					sessionId = payload.path("id");
				JsonNode cwd = payload.path("cwd");
				if (record.path("type").asString("").equals("session_meta") && sessionId.isString() &&
					CodexPolicyReader.THREAD_ID.matcher(sessionId.stringValue()).matches() && cwd.isString())
				{
					Path directory = Path.of(cwd.stringValue());
					if (directory.isAbsolute())
						return new Meta(sessionId.stringValue(), directory.normalize());
				}
			}
			catch (JacksonException | InvalidPathException _)
			{
				// Refused below
			}
		}
		throw new PolicyRefusal("the first record of the rollout " + rollout + " does not name the thread's " +
			"session and absolute working directory", "Update CAT to a release that supports this Codex release, " +
			"then start a new Codex session");
	}

	/**
	 * What a rollout's first record says about its thread.
	 *
	 * @param sessionId the identifier of the session that the thread belongs to
	 * @param cwd       the thread's absolute working directory
	 */
	private record Meta(String sessionId, Path cwd)
	{
	}
}
