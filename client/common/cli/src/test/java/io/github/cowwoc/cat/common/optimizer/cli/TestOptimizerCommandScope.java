package io.github.cowwoc.cat.common.optimizer.cli;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.fakes.ManualClock;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.execution.CapCalibrator;
import io.github.cowwoc.cat.common.optimizer.execution.FixedCalibrator;
import io.github.cowwoc.cat.common.optimizer.port.FakeHostInterruptSignal;
import io.github.cowwoc.cat.common.optimizer.port.FakeProfileInventory;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * The {@link OptimizerCommandScope} of a test: fake sessions and host interrupts, in-memory forks, a fixed cap
 * calibration, a manual clock, a fake version launcher and the campaign store of a project directory that the test
 * creates.
 * <p>
 * The launcher reports version {@code 1.0} and counts how often it runs. The clock starts at
 * {@code 2026-10-01T00:00:00Z}, and campaign identifiers, split salts and draw seeds come from a seeded generator.
 *
 * @param <S> the type of the session port
 */
final class TestOptimizerCommandScope<S extends SessionPort> extends OptimizerCommandScope
{
	private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
	/**
	 * A seed whose first salts give every test of the fixture derivation at least 2 key-value combinations in each
	 * part.
	 */
	private static final long SEED = 53;
	private final Harness harness;
	private final Path directory;
	private final JsonMapper json = JsonMapper.builder().build();
	private final ManualClock clock = new ManualClock(START);
	private final RandomGenerator random = new Random(SEED);
	private final FakeHostInterruptSignal interrupts = new FakeHostInterruptSignal();
	private final FixedCalibrator calibrator = new FixedCalibrator(FixedCalibrator.STANDARD_CAP);
	private final AtomicInteger versionReads = new AtomicInteger();
	private final InMemoryForks forks;
	private final S sessions;
	private final FakeProfileInventory profiles = new FakeProfileInventory();

	/**
	 * Creates a scope whose sessions end only when the test ends them.
	 *
	 * @param harness   the harness that runs the launcher
	 * @param directory the project directory, which the test creates and deletes
	 * @return the scope
	 * @throws NullPointerException if any argument is null
	 */
	static TestOptimizerCommandScope<FakeSessionPort> of(Harness harness, Path directory)
	{
		return new TestOptimizerCommandScope<>(harness, directory, Set.of(), _ -> new FakeSessionPort());
	}

	/**
	 * Creates a scope.
	 *
	 * @param harness   the harness that runs the launcher
	 * @param directory the project directory, which the test creates and deletes
	 * @param tracked   the files, relative to the project, that the forks treat as tracked by Git
	 * @param sessions  creates the session port from the scope's clock
	 * @throws NullPointerException if any argument is null
	 */
	TestOptimizerCommandScope(Harness harness, Path directory, Set<Path> tracked,
		Function<ManualClock, S> sessions)
	{
		requireThat(harness, "harness").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(tracked, "tracked").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		this.harness = harness;
		this.directory = directory;
		this.forks = new InMemoryForks(directory, tracked);
		this.sessions = sessions.apply(clock);
	}

	@Override
	public JsonMapper json()
	{
		ensureOpen();
		return json;
	}

	@Override
	public S sessions()
	{
		ensureOpen();
		return sessions;
	}

	@Override
	public FakeProfileInventory profiles()
	{
		ensureOpen();
		return profiles;
	}

	@Override
	public FakeHostInterruptSignal interrupts()
	{
		ensureOpen();
		return interrupts;
	}

	@Override
	public ManualClock clock()
	{
		ensureOpen();
		return clock;
	}

	@Override
	public Path directory()
	{
		ensureOpen();
		return directory;
	}

	@Override
	public Path home()
	{
		ensureOpen();
		return directory.resolve("home");
	}

	@Override
	Harness harness()
	{
		ensureOpen();
		return harness;
	}

	@Override
	RandomGenerator random()
	{
		ensureOpen();
		return random;
	}

	@Override
	HarnessVersion.Launcher harnessVersionLauncher()
	{
		ensureOpen();
		return _ ->
		{
			versionReads.incrementAndGet();
			return "1.0";
		};
	}

	@Override
	List<Path> loaderConfiguration()
	{
		ensureOpen();
		return List.of(Path.of(".claude/settings.json"));
	}

	@Override
	InMemoryForks forks()
	{
		ensureOpen();
		return forks;
	}

	@Override
	CapCalibrator calibrator()
	{
		ensureOpen();
		return calibrator;
	}

	/**
	 * Returns how often the version launcher ran.
	 *
	 * @return the count
	 */
	int versionReads()
	{
		return versionReads.get();
	}
}
