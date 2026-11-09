package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.BoundedInput;
import com.holdmyspot.cat.common.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What every session that a runner starts reaches beyond the limits of {@link SessionCommandLimits}, the same in
 * every harness. The session's commands may write the run's requests directory, the one directory outside the
 * session's project and home that they may write, and may read, but not write, the run's responses directory, so that
 * a link that the session plants cannot redirect the runner's writes. Their environment names the requests directory
 * and the {@code request-session} launcher of the artifact that the run uses. The runs of the requested sessions live
 * in the run's requested-runs directory, which exists before the session starts so that its sandbox denies the
 * session's commands every read below it, the logins of those runs included. It holds one run root for each session
 * that the session may request, numbered from 1, so that each of those directories exists before any requested
 * session starts, and the sandbox of each requested session denies the runs of the others.
 * <p>
 * A session that may request no session gets the same access with no run root of a requested session. A runner
 * launcher that its commands run then still hands its launch to the session's runner, instead of starting a harness
 * inside the session, and the runner refuses every such request with {@link #NO_REQUESTS_REASON}.
 *
 * @param requests  the run's requests directory, where the {@code request-session} launcher writes each request
 * @param responses the run's responses directory, where the runner answers each request
 * @param requestedRuns the run root of the requested sessions' runs
 * @param launcher  the {@code request-session} launcher of the run's artifact
 * @param shell the shell of the session's commands, in whose form the launcher prints the command that waits again
 */
public record SessionRequestAccess(Path requests, Path responses, Path requestedRuns, Path launcher, Shell shell)
{
	/**
	 * A shell that can run the request launcher.
	 */
	public enum Shell
	{
		POSIX,
		POWERSHELL
	}

	/**
	 * The variable that names the requests directory to a session's commands.
	 */
	public static final String REQUESTS_VARIABLE = "CAT_SESSION_REQUESTS";
	/**
	 * The variable that names the {@code request-session} launcher to a session's commands.
	 */
	public static final String LAUNCHER_VARIABLE = "CAT_REQUEST_SESSION";
	/**
	 * The variable that tells a session which shell form to use for the request wait command.
	 */
	public static final String SHELL_VARIABLE = "CAT_REQUEST_SESSION_SHELL";
	/**
	 * The name of the launcher that requests a session, without the extension that it has on Windows.
	 */
	public static final String LAUNCHER = "request-session";
	/**
	 * The start of each reason with which a runner refuses a request because its run allows no further session.
	 */
	private static final String NO_FURTHER_SESSIONS = "this run allows no further sessions";
	/**
	 * The reason with which a runner refuses each request of a session that may request no session, which names the
	 * options of the run's caller that would allow it.
	 */
	public static final String NO_REQUESTS_REASON = NO_FURTHER_SESSIONS + ". Its caller allows them " +
		"with --session-requests <count>, and lets a requested session's commands start sessions too with " +
		"--request-depth <levels>";

	/**
	 * The file in a run's responses directory in which the runner states how many sessions the run allows, how many it
	 * has accepted and how many levels below its requested sessions may request sessions in turn:
	 * {@code {"format_version": 1, "requests_allowed": <n>, "requests_used": <n>, "depth_remaining": <n>}}. The runner
	 * rewrites it whenever it accepts a request, so that a command can tell, before it changes anything, whether the
	 * run allows any further session.
	 */
	public static final String LIMITS_FILE = "limits.json";
	/**
	 * The most bytes of a {@link #LIMITS_FILE} that a reader reads.
	 */
	private static final int MAXIMUM_LIMITS_BYTES = 4096;

	/**
	 * Returns the reason with which a runner refuses each request after it accepted all that its options allow, which
	 * names the option of the run's caller that would allow more.
	 *
	 * @param sessionRequests how many sessions the run allows
	 * @return the reason
	 */
	public static String limitReason(int sessionRequests)
	{
		return NO_FURTHER_SESSIONS + ": it has requested all " + sessionRequests + " that its " +
			"caller's --session-requests allows. Its caller allows more with a larger --session-requests";
	}

	/**
	 * Returns the message with which a CAT command that starts sessions refuses to start one inside a run that allows
	 * no further session.
	 *
	 * @param command names the command, such as {@code "The prompt optimizer"}
	 * @param reason  the reason of the run's runner, {@link #NO_REQUESTS_REASON} or {@link #limitReason(int)}
	 * @return the message, which names the options of the run's caller that would allow the session
	 * @throws NullPointerException if {@code command} or {@code reason} are null
	 */
	public static String refusalMessage(String command, String reason)
	{
		requireThat(command, "command").isNotNull();
		requireThat(reason, "reason").isNotNull();
		return command + " refuses to start a session: " + reason + ".";
	}

	/**
	 * Indicates whether a runner refused a request because its run allows no further session.
	 *
	 * @param reason the runner's reason
	 * @return {@code true} if the reason is {@link #NO_REQUESTS_REASON} or {@link #limitReason(int)}
	 * @throws NullPointerException if {@code reason} is null
	 */
	public static boolean isRequestLimit(String reason)
	{
		requireThat(reason, "reason").isNotNull();
		return reason.startsWith(NO_FURTHER_SESSIONS);
	}

	/**
	 * Returns why the run whose requests directory a session's environment names would refuse a further session
	 * request, as its runner's {@link #LIMITS_FILE} states.
	 *
	 * @param requests the run's requests directory
	 * @return {@link #NO_REQUESTS_REASON} if the run allows no session, {@link #limitReason(int)} if it has accepted
	 *   all that it allows, or empty if it allows more or its runner has not stated its limits
	 * @throws NullPointerException if {@code requests} is null
	 * @throws IOException          if the limits cannot be read or are malformed
	 */
	public static Optional<String> furtherRequestRefusal(Path requests) throws IOException
	{
		requireThat(requests, "requests").isNotNull();
		Path file = requests.toAbsolutePath().getParent().resolve("responses").resolve(LIMITS_FILE);
		JsonNode limits;
		try
		{
			limits = JsonMapper.builder().build().readTree(BoundedInput.readUtf8(file, MAXIMUM_LIMITS_BYTES,
				"session request limits"));
		}
		catch (NoSuchFileException _)
		{
			return Optional.empty();
		}
		catch (JacksonException e)
		{
			throw new IOException("The run's session request limits are malformed: " + file, e);
		}
		if (limits.path("format_version").asInt(0) != 1 || !limits.path("requests_allowed").isInt() ||
			!limits.path("requests_used").isInt())
			throw new IOException("The run's session request limits are malformed: " + file);
		int allowed = limits.path("requests_allowed").intValue();
		if (allowed == 0)
			return Optional.of(NO_REQUESTS_REASON);
		if (limits.path("requests_used").intValue() >= allowed)
			return Optional.of(limitReason(allowed));
		return Optional.empty();
	}

	/**
	 * Creates the access.
	 *
	 * @param requests  the run's requests directory, where the {@code request-session} launcher writes each request
	 * @param responses the run's responses directory, where the runner answers each request
	 * @param requestedRuns the run root of the requested sessions' runs
	 * @param launcher  the {@code request-session} launcher of the run's artifact
	 * @param shell the shell of the session's commands, in whose form the launcher prints the command that waits again
	 * @throws NullPointerException if any of the arguments are null
	 */
	public SessionRequestAccess
	{
		requireThat(requests, "requests").isNotNull();
		requireThat(responses, "responses").isNotNull();
		requireThat(requestedRuns, "requestedRuns").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
		requireThat(shell, "shell").isNotNull();
	}

	/**
	 * Returns the requests directory of a run, which exists once a runner has given the run's session its access.
	 *
	 * @param run the run
	 * @return the directory
	 * @throws NullPointerException if {@code run} is null
	 */
	public static Path requestsDirectory(HarnessRun run)
	{
		requireThat(run, "run").isNotNull();
		return run.directory().resolve("requests");
	}

	/**
	 * Returns the responses directory of a run, which exists once a runner has given the run's session its access.
	 *
	 * @param run the run
	 * @return the directory
	 * @throws NullPointerException if {@code run} is null
	 */
	public static Path responsesDirectory(HarnessRun run)
	{
		requireThat(run, "run").isNotNull();
		return run.directory().resolve("responses");
	}

	/**
	 * Returns the run root of the runs of the sessions that a run's session requests, which exists once a runner has
	 * given the run's session its access.
	 *
	 * @param run the run
	 * @return the directory
	 * @throws NullPointerException if {@code run} is null
	 */
	public static Path requestedRunsDirectory(HarnessRun run)
	{
		requireThat(run, "run").isNotNull();
		return run.directory().resolve("requested-runs");
	}

	/**
	 * Returns the run root of one requested session.
	 *
	 * @param ordinal the session's place among the sessions that the run's session requested, from 1
	 * @return the run root
	 * @throws IllegalArgumentException if {@code ordinal} is not positive
	 */
	public Path requestedRunRoot(int ordinal)
	{
		requireThat(ordinal, "ordinal").isPositive();
		return requestedRuns.resolve(String.valueOf(ordinal));
	}

	/**
	 * Returns the access of a run's session, creating the run's requests, responses and requested-runs directories. A
	 * runner calls this before it decides what the session may read, so that its sandbox covers all three directories.
	 *
	 * @param run             the run
	 * @param artifact        the harness artifact that the run uses, whose {@code bin} directory holds the launcher
	 * @param sessionRequests how many sessions the session may request, which is the number of requested-run roots
	 *                        that this creates
	 * @param platform        the platform that the session runs on, which decides the launcher's file name
	 * @param shell           the shell of the session's commands, in whose form the launcher prints the command that
	 *                        waits again
	 * @return the access, which is never empty
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code sessionRequests} is negative
	 * @throws IOException              if a directory cannot be created
	 */
	public static Optional<SessionRequestAccess> allowing(HarnessRun run, Path artifact, int sessionRequests,
		Platform platform, Shell shell) throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(sessionRequests, "sessionRequests").isNotNegative();
		requireThat(platform, "platform").isNotNull();
		requireThat(shell, "shell").isNotNull();
		Path requests = Files.createDirectories(requestsDirectory(run));
		Path responses = Files.createDirectories(responsesDirectory(run));
		Path requestedRuns = Files.createDirectories(requestedRunsDirectory(run));
		for (int ordinal = 1; ordinal <= sessionRequests; ++ordinal)
			Files.createDirectories(requestedRuns.resolve(String.valueOf(ordinal)));
		return Optional.of(new SessionRequestAccess(requests, responses, requestedRuns, launcher(artifact, platform),
			shell));
	}

	/**
	 * Returns the {@code request-session} launcher of an artifact.
	 *
	 * @param artifact the harness artifact
	 * @param platform the platform that the launcher runs on; on Windows, the launcher is the batch file
	 * @return the launcher's absolute path
	 * @throws NullPointerException if any of the arguments are null
	 */
	public static Path launcher(Path artifact, Platform platform)
	{
		requireThat(artifact, "artifact").isNotNull();
		requireThat(platform, "platform").isNotNull();
		String name = LAUNCHER;
		if (platform.isWindows())
			name += ".cmd";
		return artifact.toAbsolutePath().normalize().resolve("bin").resolve(name);
	}

	/**
	 * Returns the runner launchers beside the request launcher. Inside a run, each one requests a session of its
	 * harness as the request launcher does.
	 *
	 * @return the {@code claude-runner} and {@code codex-runner} launchers, in that order, as batch files when the
	 *   request launcher is one
	 */
	public List<Path> runners()
	{
		String suffix = "";
		if (launcher.getFileName().toString().endsWith(".cmd"))
			suffix = ".cmd";
		return List.of(launcher.resolveSibling("claude-runner" + suffix), launcher.resolveSibling("codex-runner" + suffix));
	}

	/**
	 * Returns the variables that tell a session's commands where requests go and which launcher makes one.
	 *
	 * @return each variable's name mapped to its value
	 */
	public Map<String, String> environment()
	{
		return Map.of(REQUESTS_VARIABLE, requests.toString(), LAUNCHER_VARIABLE, launcher.toString(),
			SHELL_VARIABLE, shell.name());
	}
}
