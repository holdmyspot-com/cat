package io.github.cowwoc.cat.codex.optimizer;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.cli.MainOptimizerCommandScope;
import io.github.cowwoc.cat.common.optimizer.port.HostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.runner.HarnessCapacity;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The production scope of one {@code prompt-optimizer} command in Codex. This class alone reads the process
 * environment and the home directory, and supplies the Codex sessions and the host interrupt signal.
 */
final class CodexOptimizerCommandScope extends MainOptimizerCommandScope
{
	private static final String HARNESS = "codex";
	private static final String SESSION_ID = "CODEX_THREAD_ID";
	private static final List<Path> LOADER_CONFIGURATION = List.of(Path.of(".codex/config.toml"));
	private final SessionPort sessions;
	private final HostInterruptSignal interrupts;

	/**
	 * Creates the scope of the running process: its environment, its home directory, its working directory and the
	 * Codex artifact that holds its runtime.
	 *
	 * @throws IOException if {@code HOME} is not set
	 */
	CodexOptimizerCommandScope() throws IOException
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
	 * @param artifact    the complete Codex artifact that nested sessions install
	 * @throws NullPointerException if any argument is null
	 */
	CodexOptimizerCommandScope(Map<String, String> environment, Path home, Path directory, Path artifact)
	{
		super(HARNESS, directory, LOADER_CONFIGURATION);
		requireThat(environment, "environment").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		Path configurationDirectory = RolloutInterruptSignal.codexHome(home,
			environment.get("CODEX_HOME"));
		this.sessions = sessions(environment, home, directory, artifact, configurationDirectory, clock());
		this.interrupts = interrupts(environment, configurationDirectory, clock());
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

	/**
	 * Creates the port that runs nested sessions through the Codex runner.
	 *
	 * @param environment            the process environment
	 * @param home                   the user's home directory
	 * @param directory              the working directory
	 * @param artifact               the complete Codex artifact that nested sessions install
	 * @param configurationDirectory the host's Codex configuration directory
	 * @param clock                  the clock
	 * @return the port
	 */
	private static SessionPort sessions(Map<String, String> environment, Path home, Path directory, Path artifact,
		Path configurationDirectory, Clock clock)
	{
		Path root = directory.toAbsolutePath().normalize();
		Path runRoot = root.resolve(".cat").resolve("work").resolve("optimizer-runs").resolve(HARNESS);
		CodexSessions.Settings settings = new CodexSessions.Settings(artifact, runRoot, "", "", true,
			environment, configurationDirectory, HarnessCapacity.forHome(home, HARNESS,
				HarnessCapacity.MAXIMUM_CONCURRENCY), clock);
		return new CodexSessions(settings);
	}

	/**
	 * Returns the signal that reports the host user's interrupts. The host session's rollout is located when the
	 * signal is first polled, so commands that never poll it, such as {@code status}, do not need it.
	 *
	 * @param environment            the process environment
	 * @param configurationDirectory the host's Codex configuration directory
	 * @param clock                  the clock
	 * @return a signal that never reports an interrupt if the host session is not named in the environment. Otherwise
	 *   its first poll throws {@code IOException} if the session has no rollout.
	 */
	static HostInterruptSignal interrupts(Map<String, String> environment, Path configurationDirectory,
		Clock clock)
	{
		String sessionId = environment.get(SESSION_ID);
		if (sessionId == null || sessionId.isBlank())
			return _ -> false;
		AtomicReference<HostInterruptSignal> located = new AtomicReference<>();
		return start ->
		{
			HostInterruptSignal signal = located.get();
			if (signal == null)
			{
				Optional<Path> rollout = RolloutInterruptSignal.locate(configurationDirectory, sessionId);
				if (rollout.isEmpty())
				{
					throw new IOException("The rollout of host session " + sessionId + " is not below " +
						configurationDirectory + ", so a host interrupt cannot be detected.");
				}
				signal = new RolloutInterruptSignal(rollout.get(), clock);
				located.set(signal);
			}
			return signal.isInterruptedAfter(start);
		};
	}
}
