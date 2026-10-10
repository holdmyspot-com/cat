package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.execution.CapCalibrator;
import io.github.cowwoc.cat.common.optimizer.statistics.CapCalibration;
import io.github.cowwoc.cat.common.optimizer.workspace.Forks;
import io.github.cowwoc.cat.common.optimizer.workspace.GitForks;
import io.github.cowwoc.pouch10.core.LazyReference;
import io.github.cowwoc.pouch10.core.Reference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * The production {@link OptimizerCommandScope} that both harnesses share: real Git forks, the real cap calibration,
 * the system clock, a real process for the harness's version and the JSON mapper. Each harness's scope adds the
 * sessions and the host interrupt signal that it resolves from the process environment.
 */
public abstract class MainOptimizerCommandScope extends OptimizerCommandScope
{
	private final Harness harness;
	private final Path directory;
	private final List<Path> loaderConfiguration;
	private final Reference<JsonMapper> json = LazyReference.create(() -> JsonMapper.builder().build());
	private final Reference<RandomGenerator> random = LazyReference.create(RandomGenerator::getDefault);
	private final Reference<Forks> forks = LazyReference.create(GitForks::new);

	/**
	 * Creates an open scope.
	 *
	 * @param harness             the harness that runs the launcher
	 * @param directory           the launcher's working directory, below the project
	 * @param loaderConfiguration the files, relative to the project, that decide which prompt files the harness
	 *                            loads
	 * @throws NullPointerException     if any argument is null
	 */
	protected MainOptimizerCommandScope(Harness harness, Path directory, List<Path> loaderConfiguration)
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(loaderConfiguration, "loaderConfiguration").isNotNull();
		this.harness = harness;
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
