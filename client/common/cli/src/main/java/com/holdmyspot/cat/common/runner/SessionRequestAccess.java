package com.holdmyspot.cat.common.runner;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * What a session that may request sessions reaches beyond the limits of {@link SessionCommandLimits}, the same in
 * every harness. The session's commands may write the run's requests directory, the one directory outside the
 * session's project and home that they may write, and may read, but not write, the run's responses directory, so that
 * a link that the session plants cannot redirect the runner's writes. Their environment names the requests directory
 * and the {@code request-session} launcher of the artifact that the run uses.
 *
 * @param requests  the run's requests directory, where the {@code request-session} launcher writes each request
 * @param responses the run's responses directory, where the runner answers each request
 * @param launcher  the {@code request-session} launcher of the run's artifact
 */
public record SessionRequestAccess(Path requests, Path responses, Path launcher)
{
	/**
	 * The variable that names the requests directory to a session's commands.
	 */
	public static final String REQUESTS_VARIABLE = "CAT_SESSION_REQUESTS";
	/**
	 * The variable that names the {@code request-session} launcher to a session's commands.
	 */
	public static final String LAUNCHER_VARIABLE = "CAT_REQUEST_SESSION";
	/**
	 * The name of the launcher that requests a session, without the extension that it has on Windows.
	 */
	public static final String LAUNCHER = "request-session";

	/**
	 * Creates the access.
	 *
	 * @param requests  the run's requests directory, where the {@code request-session} launcher writes each request
	 * @param responses the run's responses directory, where the runner answers each request
	 * @param launcher  the {@code request-session} launcher of the run's artifact
	 * @throws NullPointerException if any of the arguments are null
	 */
	public SessionRequestAccess
	{
		requireThat(requests, "requests").isNotNull();
		requireThat(responses, "responses").isNotNull();
		requireThat(launcher, "launcher").isNotNull();
	}

	/**
	 * Returns the requests directory of a run, which exists only if the run's session may request sessions.
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
	 * Returns the responses directory of a run, which exists only if the run's session may request sessions.
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
	 * Returns the access of a run's session, creating the run's requests and responses directories if the session may
	 * request any session. A runner calls this before it decides what the session may read, so that its sandbox
	 * covers both directories.
	 *
	 * @param run             the run
	 * @param artifact        the harness artifact that the run uses, whose {@code bin} directory holds the launcher
	 * @param sessionRequests how many sessions the session may request
	 * @param platform        the platform that the session runs on, which decides the launcher's file name
	 * @return the access, or empty if {@code sessionRequests} is {@code 0}, in which case nothing is created
	 * @throws NullPointerException     if any of the arguments are null
	 * @throws IllegalArgumentException if {@code sessionRequests} is negative
	 * @throws IOException              if a directory cannot be created
	 */
	public static Optional<SessionRequestAccess> allowing(HarnessRun run, Path artifact, int sessionRequests,
		Platform platform) throws IOException
	{
		requireThat(run, "run").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		requireThat(sessionRequests, "sessionRequests").isNotNegative();
		requireThat(platform, "platform").isNotNull();
		if (sessionRequests == 0)
			return Optional.empty();
		Path requests = Files.createDirectories(requestsDirectory(run));
		Path responses = Files.createDirectories(responsesDirectory(run));
		return Optional.of(new SessionRequestAccess(requests, responses, launcher(artifact, platform)));
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
	 * Returns the variables that tell a session's commands where requests go and which launcher makes one.
	 *
	 * @return each variable's name mapped to its value
	 */
	public Map<String, String> environment()
	{
		return Map.of(REQUESTS_VARIABLE, requests.toString(), LAUNCHER_VARIABLE, launcher.toString());
	}
}
