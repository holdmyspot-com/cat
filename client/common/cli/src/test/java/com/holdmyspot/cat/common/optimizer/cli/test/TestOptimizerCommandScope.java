package com.holdmyspot.cat.common.optimizer.cli.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.FileReplacer;
import com.holdmyspot.cat.common.SessionDirectory;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import com.holdmyspot.cat.common.fakes.test.ManualClock;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.cli.HarnessVersion;
import com.holdmyspot.cat.common.optimizer.cli.OptimizerCommandScope;
import com.holdmyspot.cat.common.optimizer.cli.AbstractOptimizerCommandScope;
import com.holdmyspot.cat.common.fakes.test.TestJvmScope;
import com.holdmyspot.cat.common.optimizer.execution.CapCalibrator;
import com.holdmyspot.cat.common.optimizer.execution.test.FixedCalibrator;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.test.FakeHostInterruptSignal;
import com.holdmyspot.cat.common.optimizer.port.test.FakeProfileInventory;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.test.SessionHarnesses;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * The command runs in the host session {@link #SESSION_ID} unless the test names another one.
 * <p>
 * The launcher reports version {@code 1.0} and counts how often it runs. The clock starts at
 * {@code 2026-10-01T00:00:00Z}, and campaign identifiers, split salts and draw seeds come from a seeded generator.
 *
 * @param <S> the type of the session port
 */
final class TestOptimizerCommandScope<S extends SessionPort> extends AbstractOptimizerCommandScope
{
	/**
	 * The ID of the host session that runs a command unless the test names another one.
	 */
	static final String SESSION_ID = "0f8e2c4a-7b1d-4e6f-9a3c-5d2b8e1f4a7c";
	private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
	/**
	 * A seed whose first salts give every test of the fixture derivation at least 2 outcome-changing combinations in each
	 * part.
	 */
	private static final long SEED = 432_969;
	private final Harness harness;
	private final String sessionId;
	private final Path directory;
	private final ManualClock clock = new ManualClock(START);
	private final RandomGenerator random = new Random(SEED);
	private final FakeHostInterruptSignal interrupts = new FakeHostInterruptSignal();
	private final FixedCalibrator calibrator = new FixedCalibrator(FixedCalibrator.STANDARD_CAP);
	private final AtomicInteger versionReads = new AtomicInteger();
	private final InMemoryForks forks;
	private final S sessions;
	private final FakeProfileInventory profiles = new FakeProfileInventory();
	private String userLoaderSettings;

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
		return of(harness, SESSION_ID, directory);
	}

	/**
	 * Creates a fresh command scope backed by the forks that earlier commands used.
	 *
	 * @param harness the harness that runs the launcher
	 * @param directory the project directory, which the test creates and deletes
	 * @param forks the existing in-memory fork store
	 * @return the scope
	 * @throws NullPointerException if any argument is null
	 */
	static TestOptimizerCommandScope<FakeSessionPort> of(Harness harness, Path directory, InMemoryForks forks)
	{
		return new TestOptimizerCommandScope<>(harness, SESSION_ID, directory, forks, _ -> new FakeSessionPort());
	}

	/**
	 * Creates a scope whose command runs in a named host session, and whose sessions end only when the test ends
	 * them.
	 *
	 * @param harness   the harness that runs the launcher
	 * @param sessionId the ID of the host session that runs the command
	 * @param directory the project directory, which the test creates and deletes
	 * @return the scope
	 * @throws NullPointerException if any argument is null
	 */
	static TestOptimizerCommandScope<FakeSessionPort> of(Harness harness, String sessionId, Path directory)
	{
		return new TestOptimizerCommandScope<>(harness, sessionId, directory, Set.of(), _ -> new FakeSessionPort());
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
		this(harness, SESSION_ID, directory, tracked, sessions);
	}

	/**
	 * Creates a scope whose command runs in a named host session.
	 *
	 * @param harness   the harness that runs the launcher
	 * @param sessionId the ID of the host session that runs the command
	 * @param directory the project directory, which the test creates and deletes
	 * @param tracked   the files, relative to the project, that the forks treat as tracked by Git
	 * @param sessions  creates the session port from the scope's clock
	 * @throws NullPointerException if any argument is null
	 */
	TestOptimizerCommandScope(Harness harness, String sessionId, Path directory, Set<Path> tracked,
		Function<ManualClock, S> sessions)
	{
		this(harness, sessionId, directory, new InMemoryForks(directory, tracked), sessions);
	}

	/**
	 * Creates a scope with an existing fork store.
	 *
	 * @param harness the harness that runs the launcher
	 * @param sessionId the ID of the host session that runs the command
	 * @param directory the project directory, which the test creates and deletes
	 * @param forks the existing in-memory fork store
	 * @param sessions creates the session port from the scope's clock
	 * @throws NullPointerException if any argument is null
	 */
	private TestOptimizerCommandScope(Harness harness, String sessionId, Path directory, InMemoryForks forks,
		Function<ManualClock, S> sessions)
	{
		super(new TestJvmScope());
		requireThat(harness, "harness").isNotNull();
		requireThat(sessionId, "sessionId").isNotNull();
		requireThat(directory, "directory").isNotNull();
		requireThat(forks, "forks").isNotNull();
		requireThat(sessions, "sessions").isNotNull();
		this.harness = harness;
		this.sessionId = sessionId;
		this.directory = directory;
		this.forks = forks;
		this.sessions = sessions.apply(clock);
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
	public FileReplacer fileReplacer()
	{
		ensureOpen();
		return Yielding.fileReplacer();
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
	public Path launcher()
	{
		ensureOpen();
		return directory.toAbsolutePath().resolve("bin/prompt-optimizer");
	}

	@Override
	protected SessionDirectory openSessionDirectory(Path projectRoot) throws IOException
	{
		return SessionDirectory.open(projectRoot, sessionId, SessionHarnesses.HARNESS_A,
			directory.resolve("configuration"), clock().instant(), json());
	}

	@Override
	protected Harness harness()
	{
		ensureOpen();
		return harness;
	}

	@Override
	protected RandomGenerator random()
	{
		ensureOpen();
		return random;
	}

	@Override
	protected HarnessVersion.Launcher harnessVersionLauncher()
	{
		ensureOpen();
		return _ ->
		{
			versionReads.incrementAndGet();
			return "1.0";
		};
	}

	@Override
	protected List<Path> loaderConfiguration()
	{
		ensureOpen();
		return List.of(Path.of(".claude/settings.json"));
	}

	@Override
	protected Optional<String> userLoaderSettings()
	{
		ensureOpen();
		return Optional.ofNullable(userLoaderSettings);
	}

	/**
	 * Sets the user's settings that decide which prompt files the harness loads.
	 *
	 * @param userLoaderSettings the settings
	 * @throws NullPointerException if {@code userLoaderSettings} is null
	 */
	void userLoaderSettings(String userLoaderSettings)
	{
		requireThat(userLoaderSettings, "userLoaderSettings").isNotNull();
		this.userLoaderSettings = userLoaderSettings;
	}

	@Override
	protected InMemoryForks forks()
	{
		ensureOpen();
		return forks;
	}

	@Override
	protected CapCalibrator calibrator()
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
