package io.github.cowwoc.cat.claude.optimizer;

import static io.github.cowwoc.cat.claude.ClaudePluginInfo.HARNESS_ID;

import io.github.cowwoc.cat.claude.ClaudeConfiguration;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.adapter.DeferredInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.adapter.RunnerSessions;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.cli.MainOptimizerCommandScope;
import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.ProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The production scope of one {@code prompt-optimizer} command in Claude Code. This class alone reads the process
 * environment and the home directory, and supplies the Claude sessions, the host interrupt signal and the Claude
 * profile inventory.
 */
final class ClaudeOptimizerCommandScope extends MainOptimizerCommandScope
{
	private static final String SESSION_ID = "CLAUDE_CODE_SESSION_ID";
	private static final List<Path> LOADER_CONFIGURATION = List.of(Path.of(".claude/settings.json"),
		Path.of(".claude/settings.local.json"), Path.of(".mcp.json"));
	private final SessionPort sessions;
	private final HostInterruptSignal interrupts;
	private final ProfileInventory profiles;

	/**
	 * Creates the scope of the running process: its environment, its home directory, its working directory and the
	 * Claude artifact that holds its runtime.
	 *
	 * @throws IOException if {@code HOME} is not set
	 */
	ClaudeOptimizerCommandScope() throws IOException
	{
		this(System.getenv(), home(System.getenv()), Path.of("").toAbsolutePath(),
			Path.of(System.getProperty("java.home")).toAbsolutePath().getParent());
	}

	/**
	 * Creates a scope.
	 *
	 * @param environment the process environment
	 * @param home        the user's home directory
	 * @param directory   the working directory
	 * @param artifact    the complete Claude artifact that nested sessions install
	 * @throws NullPointerException if any argument is null
	 */
	ClaudeOptimizerCommandScope(Map<String, String> environment, Path home, Path directory, Path artifact)
	{
		super(Harness.CLAUDE, directory, LOADER_CONFIGURATION);
		requireThat(environment, "environment").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		Path configurationDirectory = ClaudeConfiguration.resolveDirectory(home,
			environment.get("CLAUDE_CONFIG_DIR"));
		this.sessions = sessions(environment, home, directory, artifact, configurationDirectory, clock());
		this.interrupts = interrupts(environment, configurationDirectory, clock());
		this.profiles = new ClaudeProfileInventory(artifact);
	}

	/**
	 * Returns the user's home directory from the process environment.
	 *
	 * @param environment the process environment
	 * @return the home directory
	 * @throws IOException if {@code HOME} is not set
	 */
	private static Path home(Map<String, String> environment) throws IOException
	{
		String home = environment.get("HOME");
		if (home == null || home.isBlank())
			throw new IOException("HOME is not set.");
		return Path.of(home);
	}

	@Override
	public SessionPort sessions()
	{
		ensureOpen();
		return sessions;
	}

	@Override
	public HostInterruptSignal interrupts()
	{
		ensureOpen();
		return interrupts;
	}

	@Override
	public ProfileInventory profiles()
	{
		ensureOpen();
		return profiles;
	}

	/**
	 * Creates the port that runs nested sessions through the Claude runner.
	 *
	 * @param environment            the process environment
	 * @param home                   the user's home directory
	 * @param directory              the working directory
	 * @param artifact               the complete Claude artifact that nested sessions install
	 * @param configurationDirectory the host's Claude configuration directory
	 * @param clock                  the clock
	 * @return the port
	 */
	private static SessionPort sessions(Map<String, String> environment, Path home, Path directory, Path artifact,
		Path configurationDirectory, Clock clock)
	{
		Path root = directory.toAbsolutePath().normalize();
		Path runRoot = root.resolve(".cat").resolve("work").resolve("optimizer-runs").resolve(HARNESS_ID);
		RunnerSessions.Settings settings = new RunnerSessions.Settings(artifact, runRoot, "", true,
			environment, configurationDirectory, HarnessCapacity.forHome(home, HARNESS_ID,
				HarnessCapacity.MAXIMUM_CONCURRENCY), clock);
		return new ClaudeSessions(settings);
	}

	/**
	 * Returns the signal that reports the host user's interrupts. The host session's transcript is located when the
	 * signal is first polled, so commands that never poll it, such as {@code status}, do not need it.
	 *
	 * @param environment            the process environment
	 * @param configurationDirectory the host's Claude configuration directory
	 * @param clock                  the clock
	 * @return a signal that never reports an interrupt if the host session is not named in the environment. Otherwise
	 *   its first poll throws {@code IOException} if the session has no transcript.
	 */
	static HostInterruptSignal interrupts(Map<String, String> environment, Path configurationDirectory,
		Clock clock)
	{
		String sessionId = environment.get(SESSION_ID);
		if (sessionId == null || sessionId.isBlank())
			return _ -> false;
		return new DeferredInterruptSignal(() ->
		{
			Optional<Path> transcript = TranscriptInterruptSignal.locate(configurationDirectory, sessionId);
			if (transcript.isEmpty())
			{
				throw new IOException("The transcript of host session " + sessionId + " is not below " +
					configurationDirectory + ", so a host interrupt cannot be detected.");
			}
			return new TranscriptInterruptSignal(transcript.get(), clock);
		});
	}
}
