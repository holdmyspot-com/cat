package com.holdmyspot.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.MainJvmScope;
import com.holdmyspot.cat.common.Platform;
import com.holdmyspot.cat.common.Waiting;
import com.holdmyspot.cat.common.SessionHarness;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.optimizer.adapter.RunnerSessions;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.execution.CapCalibrator;
import com.holdmyspot.cat.common.optimizer.statistics.CapCalibration;
import com.holdmyspot.cat.common.optimizer.workspace.Forks;
import com.holdmyspot.cat.common.optimizer.workspace.GitForks;
import com.holdmyspot.cat.common.runner.HarnessEnvironment;
import com.holdmyspot.cat.common.runner.HarnessMarkers;
import com.holdmyspot.cat.common.runner.HarnessSandbox;
import com.holdmyspot.cat.common.runner.LaunchRoute;
import com.holdmyspot.cat.common.runner.PluginDirectory;
import com.holdmyspot.cat.common.runner.SessionRequestAccess;
import com.holdmyspot.cat.common.runner.launcher.client.SessionLauncherClient;
import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * The production {@link OptimizerCommandScope} that both harnesses share: real Git forks, the real cap calibration,
 * the system clock, a real process for the harness's version, the JSON mapper and the CAT session directory of the host
 * session that the process environment names. Each harness's scope adds the sessions and the host interrupt signal
 * that it resolves from the process environment.
 */
public abstract class MainOptimizerCommandScope extends AbstractOptimizerCommandScope
{
	/**
	 * The launcher's name in the runtime image's {@code bin} directory.
	 */
	private static final String LAUNCHER = "prompt-optimizer";
	private final Harness harness;
	private final HarnessSandbox trialSandbox;
	private final SessionHarness sessionHarness;
	private final HarnessMarkers markers;
	private final Map<String, String> environment;
	private final Path configurationDirectory;
	private final Path directory;
	private final List<Path> loaderConfiguration;
	private final Reference<RandomGenerator> random = LazyReference.create(RandomGenerator::getDefault);
	private final Reference<FileReplacer> fileReplacer;
	private final Reference<Forks> forks;

	/**
	 * Creates an open scope.
	 *
	 * @param harness                the harness that runs the launcher
	 * @param trialSandbox           decides whether {@code harness} sandboxes the campaign's trials on a platform
	 * @param sessionHarness         the parts of the harness's CAT session directories
	 * @param markers                reads which harnesses' sessions run a command from its environment
	 * @param environment            the process environment, whose session variable names the host session
	 * @param configurationDirectory the host harness's configuration directory
	 * @param directory              the launcher's working directory, below the project
	 * @param loaderConfiguration    the files, relative to the project, that decide which prompt files the harness
	 *                               loads
	 * @param waiting                how the process waits between the attempts of a retried operation
	 * @throws NullPointerException     if any argument is null
	 */
	protected MainOptimizerCommandScope(Harness harness, HarnessSandbox trialSandbox, SessionHarness sessionHarness,
		HarnessMarkers markers, Map<String, String> environment, Path configurationDirectory, Path directory,
		List<Path> loaderConfiguration, Waiting waiting)
	{
		super(new MainJvmScope(environment, waiting));
		this.fileReplacer = LazyReference.create(() -> FileReplacer.retrying(waiting));
		this.forks = LazyReference.create(() -> new GitForks(fileReplacer.getValue()));
		requireThat(harness, "harness").isNotNull();
		requireThat(trialSandbox, "trialSandbox").isNotNull();
		requireThat(sessionHarness, "sessionHarness").isNotNull();
		requireThat(markers, "markers").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(configurationDirectory, "configurationDirectory").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(loaderConfiguration, "loaderConfiguration").isNotNull();
		this.harness = harness;
		this.trialSandbox = trialSandbox;
		this.sessionHarness = sessionHarness;
		this.markers = markers;
		this.environment = Map.copyOf(environment);
		this.configurationDirectory = configurationDirectory;
		this.directory = directory;
		this.loaderConfiguration = List.copyOf(loaderConfiguration);
	}



	@Override
	public final FileReplacer fileReplacer()
	{
		ensureOpen();
		return fileReplacer.getValue();
	}

	@Override
	public final Path directory()
	{
		ensureOpen();
		return directory;
	}

	/**
	 * Returns the {@code prompt-optimizer} launcher of the runtime image that runs this command: the launcher in the
	 * {@code bin} directory beside the image's Java home, with the {@code .cmd} suffix on Windows.
	 *
	 * @return the launcher's absolute path
	 * @throws IllegalStateException if this scope is closed
	 */
	@Override
	public final Path launcher()
	{
		ensureOpen();
		String name = LAUNCHER;
		if (Platform.current().isWindows())
			name += ".cmd";
		return PluginDirectory.of(MainOptimizerCommandScope.class).resolve("bin").resolve(name);
	}

	/**
	 * Opens the CAT session directory of the host session that the harness's session variable names.
	 *
	 * @param projectRoot the project root
	 * @return the directory
	 * @throws CommandRefusedException if the session variable is not set
	 * @throws IOException if the session variable does not hold a session ID, or the directory cannot be created
	 */
	@Override
	protected final SessionDirectory openSessionDirectory(Path projectRoot)
		throws CommandRefusedException, IOException
	{
		String sessionId = environment.get(sessionHarness.sessionVariable());
		if (sessionId == null || sessionId.isBlank())
		{
			throw new CommandRefusedException("A campaign belongs to the " + harness.commandName() + " session that " +
				"runs it, but " + sessionHarness.sessionVariable() + " is not set. Run this command from a tool shell " +
				"of a " + harness.commandName() + " session.");
		}
		return SessionDirectory.open(projectRoot, sessionId.strip(), sessionHarness, configurationDirectory,
			clock().instant(), json());
	}

	@Override
	protected final Harness harness()
	{
		ensureOpen();
		return harness;
	}

	@Override
	protected final HarnessSandbox trialSandbox()
	{
		ensureOpen();
		return trialSandbox;
	}

	/**
	 * Returns why this command is refused when no harness session runs it: the environment holds no marker of Claude
	 * Code or Codex, as in a terminal. A session variable without its harness's marker does not make a harness run
	 * the command.
	 *
	 * @return the message that tells the user to run the command from a Claude Code or Codex session, or empty if a
	 *   harness session runs the command
	 * @throws IllegalStateException if this scope is closed
	 */
	@Override
	protected final Optional<String> noHarnessRefusal()
	{
		ensureOpen();
		if (!markers.running(environment).isEmpty())
			return Optional.empty();
		return Optional.of("The prompt optimizer runs only from a Claude Code or Codex session, and no harness " +
			"session runs this command. Run it from a Claude Code or Codex session.");
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * Inside a run whose runner lets its session request sessions, the command's sessions are requests to that runner,
	 * so the command is also refused when the run's limits, as its runner states them, allow no further session.
	 */
	@Override
	protected final Optional<String> runRefusal()
	{
		ensureOpen();
		Optional<String> refusal = HarnessEnvironment.runRefusal(RunnerSessions.COMMAND, environment);
		if (refusal.isPresent())
			return refusal;
		String id = harness.commandName();
		if (!(LaunchRoute.decide(id, id, markers.running(environment), new String[0],
			environment) instanceof LaunchRoute.Forward(Path requests)))
			return Optional.empty();
		try
		{
			return SessionRequestAccess.furtherRequestRefusal(requests).
				map(reason -> SessionRequestAccess.refusalMessage(RunnerSessions.COMMAND, reason));
		}
		catch (IOException _)
		{
			// The run's runner still refuses each session that its limits do not allow, which pauses the campaign
			return Optional.empty();
		}
	}

	/**
	 * Returns why the session launcher would refuse every session, but only when this command's sessions go to the
	 * session launcher, as their bridge decides from the environment: when this scope's harness runs the command
	 * outside a run. On every other route no launcher needs to serve the host session: inside a run the run's runner
	 * serves it, and a route that refuses its sessions refuses each of them, with its reason, when it starts.
	 *
	 * @return the message that names the reason and the user's remedy, or empty if a launcher may start sessions or
	 *   the sessions do not go to a session launcher
	 * @throws IllegalStateException if this scope is closed
	 * @throws IOException           if the launchers' state cannot be listed
	 */
	@Override
	protected final Optional<String> launchRefusal() throws IOException
	{
		ensureOpen();
		String id = harness.commandName();
		return switch (LaunchRoute.decide(id, id, markers.running(environment), new String[0], environment))
		{
			case LaunchRoute.Launcher _ -> SessionLauncherClient.production(environment, waiting()).refusal();
			case LaunchRoute.Forward _, LaunchRoute.Refused _, LaunchRoute.Bounded _, LaunchRoute.InPlace _ ->
				Optional.empty();
		};
	}

	@Override
	protected final RandomGenerator random()
	{
		ensureOpen();
		return random.getValue();
	}

	@Override
	protected final HarnessVersion.Launcher harnessVersionLauncher()
	{
		ensureOpen();
		return HarnessVersion.process();
	}

	@Override
	protected final List<Path> loaderConfiguration()
	{
		ensureOpen();
		return loaderConfiguration;
	}

	@Override
	protected final Forks forks()
	{
		ensureOpen();
		return forks.getValue();
	}

	@Override
	protected final CapCalibrator calibrator()
	{
		ensureOpen();
		return CapCalibration::calibrate;
	}
}
