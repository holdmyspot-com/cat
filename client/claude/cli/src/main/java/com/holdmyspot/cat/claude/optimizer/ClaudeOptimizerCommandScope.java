package com.holdmyspot.cat.claude.optimizer;

import com.holdmyspot.cat.common.JvmScope;

import static com.holdmyspot.cat.claude.ClaudePluginInfo.HARNESS_ID;

import com.holdmyspot.cat.claude.ClaudeConfiguration;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.ProjectRuleLoader;
import com.holdmyspot.cat.common.optimizer.adapter.DeferredInterruptSignal;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.cli.MainOptimizerCommandScope;
import com.holdmyspot.cat.common.optimizer.port.HostInterruptSignal;
import com.holdmyspot.cat.common.optimizer.port.ProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.runner.HarnessCapacity;
import com.holdmyspot.cat.claude.runner.ClaudeConnectionSettings;
import com.holdmyspot.cat.claude.secrets.ClaudeOptimizerCommandScopeAccess;
import com.holdmyspot.cat.claude.ClaudeSessionHarness;
import com.holdmyspot.cat.claude.secrets.ClaudeOptimizerCommandScopeSecrets;

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
public final class ClaudeOptimizerCommandScope extends MainOptimizerCommandScope
{
	static
	{
		ClaudeOptimizerCommandScopeSecrets.setClaudeOptimizerCommandScopeAccess(new ClaudeOptimizerCommandScopeAccess()
		{
			@Override
			public ClaudeOptimizerCommandScope newClaudeOptimizerCommandScope(Map<String, String> environment, Path home,
				Path directory, Path artifact)
			{
				return new ClaudeOptimizerCommandScope(environment, home, directory, artifact);
			}

			@Override
			public HostInterruptSignal interrupts(Map<String, String> environment, Path configurationDirectory, Clock clock)
			{
				return ClaudeOptimizerCommandScope.interrupts(environment, configurationDirectory, clock);
			}
		});
	}

	private static final String SESSION_ID = "CLAUDE_CODE_SESSION_ID";
	private static final List<Path> LOADER_CONFIGURATION = List.of(Path.of(".claude/settings.json"),
		Path.of(".claude/settings.local.json"), Path.of(".mcp.json"));
	private final Map<String, String> environment;
	private final Path artifact;
	private final Path configurationDirectory;
	private final HostInterruptSignal interrupts;
	private final ProfileInventory profiles;
	private final Path home;
	private SessionPort sessions;

	/**
	 * Creates the scope of the running process: its environment, its home directory, its working directory and the
	 * Claude artifact that holds its runtime.
	 *
	 * @throws IOException if the environment does not set the platform's home variable
	 */
	ClaudeOptimizerCommandScope() throws IOException
	{
		this(Platform.current().environment(System.getenv()), home(Platform.current().environment(System.getenv())),
			Path.of("").toAbsolutePath(),
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
		requireThat(environment, "environment").isNotNull();
		requireThat(home, "home").isNotNull();
		requireThat(artifact, "artifact").isNotNull();
		Path configurationDirectory = ClaudeConfiguration.resolveDirectory(home,
			environment.get("CLAUDE_CONFIG_DIR"));
		super(Harness.CLAUDE, ClaudeSessionHarness.HARNESS, environment, configurationDirectory, directory,
			LOADER_CONFIGURATION);
		this.environment = Map.copyOf(environment);
		this.artifact = artifact;
		this.configurationDirectory = configurationDirectory;
		this.home = home;
		this.interrupts = interrupts(environment, configurationDirectory, clock());
		this.profiles = new ClaudeProfileInventory(artifact);
	}

	/**
	 * Returns the user's home directory from the process environment.
	 *
	 * @param environment the process environment
	 * @return the home directory
	 * @throws IOException if the environment does not set the platform's home variable
	 */
	private static Path home(Map<String, String> environment) throws IOException
	{
		return Platform.current().homeDirectory(environment);
	}

	@Override
	public SessionPort sessions() throws IOException
	{
		ensureOpen();
		if (sessions == null)
		{
			sessions = sessions(this, environment, home, hostProject(), runRoot(), artifact, configurationDirectory,
				this::heldOutDirectories, clock(), fileReplacer());
		}
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

	@Override
	public Path home()
	{
		ensureOpen();
		return home;
	}

	@Override
	protected Optional<String> userLoaderSettings() throws IOException
	{
		ensureOpen();
		return ClaudeConnectionSettings.instructionFilesMode(json(), configurationDirectory.resolve("settings.json")).
			map(mode -> "instructionFiles=" + mode);
	}

	/**
	 * Creates the port that runs nested sessions through the Claude runner.
	 *
	 * @param jvm the borrowed process scope
	 * @param environment            the process environment
	 * @param home                   the user's home directory
	 * @param hostProject            the project of the host session, which holds the run root
	 * @param runRoot                the directory that holds the runs of the host session's campaigns
	 * @param artifact               the complete Claude artifact that nested sessions install
	 * @param configurationDirectory the host's Claude configuration directory
	 * @param heldOut                lists the campaigns' held-out directories
	 * @param clock                  the clock
	 * @param replacer               replaces the files of the sessions' runs
	 * @return the port
	 */
	private static SessionPort sessions(JvmScope jvm, Map<String, String> environment, Path home, Path hostProject,
		Path runRoot,
		Path artifact, Path configurationDirectory, RunnerSessions.HeldOutPaths heldOut, Clock clock,
		FileReplacer replacer)
	{
		RunnerSessions.Settings settings = new RunnerSessions.Settings(jvm, artifact, runRoot, heldOut, "", true,
			environment, configurationDirectory, HarnessCapacity.forHome(home, HARNESS_ID,
				HarnessCapacity.MAXIMUM_CONCURRENCY), clock, replacer, Optional.of(ProjectRuleLoader.rulesDirectory(home)),
			hostProject);
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
