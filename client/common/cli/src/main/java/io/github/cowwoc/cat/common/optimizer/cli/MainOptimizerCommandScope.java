package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.Platform;
import io.github.cowwoc.cat.common.SessionHarness;
import io.github.cowwoc.cat.common.SessionWorkDirectory;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.execution.CapCalibrator;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.workspace.Forks;
import io.github.cowwoc.cat.common.optimizer.workspace.GitForks;
import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * The production {@link OptimizerCommandScope} that both harnesses share: real Git forks, the real cap calibration,
 * the system clock, a real process for the harness's version, the JSON mapper and the work directory of the host
 * session that the process environment names. Each harness's scope adds the sessions and the host interrupt signal
 * that it resolves from the process environment.
 */
public abstract class MainOptimizerCommandScope extends OptimizerCommandScope
{
	/**
	 * The launcher's name in the runtime image's {@code bin} directory.
	 */
	private static final String LAUNCHER = "prompt-optimizer";
	private final Harness harness;
	private final SessionHarness sessionHarness;
	private final Map<String, String> environment;
	private final Path configurationDirectory;
	private final Path directory;
	private final List<Path> loaderConfiguration;
	private final Reference<JsonMapper> json = LazyReference.create(() -> JsonMapper.builder().build());
	private final Reference<RandomGenerator> random = LazyReference.create(RandomGenerator::getDefault);
	private final Reference<Forks> forks = LazyReference.create(GitForks::new);

	/**
	 * Creates an open scope.
	 *
	 * @param harness                the harness that runs the launcher
	 * @param sessionHarness         the parts of the harness's session work directories
	 * @param environment            the process environment, whose session variable names the host session
	 * @param configurationDirectory the host harness's configuration directory
	 * @param directory              the launcher's working directory, below the project
	 * @param loaderConfiguration    the files, relative to the project, that decide which prompt files the harness
	 *                               loads
	 * @throws NullPointerException     if any argument is null
	 */
	protected MainOptimizerCommandScope(Harness harness, SessionHarness sessionHarness,
		Map<String, String> environment, Path configurationDirectory, Path directory, List<Path> loaderConfiguration)
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(sessionHarness, "sessionHarness").isNotNull();
		requireThat(environment, "environment").isNotNull();
		requireThat(configurationDirectory, "configurationDirectory").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(loaderConfiguration, "loaderConfiguration").isNotNull();
		this.harness = harness;
		this.sessionHarness = sessionHarness;
		this.environment = Map.copyOf(environment);
		this.configurationDirectory = configurationDirectory;
		this.directory = directory;
		this.loaderConfiguration = List.copyOf(loaderConfiguration);
	}

	@Override
	public final JsonMapper json()
	{
		ensureOpen();
		return json.getValue();
	}

	@Override
	public final Clock clock()
	{
		ensureOpen();
		return Clock.systemUTC();
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
		return Path.of(System.getProperty("java.home")).toAbsolutePath().normalize().resolveSibling("bin").
			resolve(name);
	}

	/**
	 * Opens the work directory of the host session that the harness's session variable names.
	 *
	 * @param projectRoot the project root
	 * @return the directory
	 * @throws CommandRefusedException if the session variable is not set
	 * @throws IOException if the session variable does not hold a session ID, or the directory cannot be created
	 */
	@Override
	protected final SessionWorkDirectory openSessionWorkDirectory(Path projectRoot)
		throws CommandRefusedException, IOException
	{
		String sessionId = environment.get(sessionHarness.sessionVariable());
		if (sessionId == null || sessionId.isBlank())
		{
			throw new CommandRefusedException("A campaign belongs to the " + harness.commandName() + " session that " +
				"runs it, but " + sessionHarness.sessionVariable() + " is not set. Run this command from a tool shell " +
				"of a " + harness.commandName() + " session.");
		}
		return SessionWorkDirectory.open(projectRoot, sessionId.strip(), sessionHarness, configurationDirectory,
			clock().instant(), json());
	}

	@Override
	protected final Harness harness()
	{
		ensureOpen();
		return harness;
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
