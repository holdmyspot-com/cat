package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort.FakeSession;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.report.ReportStatus;
import io.github.cowwoc.cat.common.optimizer.report.Status;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Covers a slice's admission cutoff, its grace period and the runner that waits between steps.
 * <p>
 * Tests that advance a driver use a contract with a 2-minute trial limit and a 3-minute slice with a 1-minute grace
 * period.
 * Until five trial durations are measured, another trial is admitted only within the first minute of the slice.
 */
public final class SliceTest
{
	/**
	 * The time limit of every test of the contract.
	 */
	private static final Duration TIME_LIMIT = Duration.ofMinutes(2);
	/**
	 * The slices' settings.
	 */
	private static final SliceSettings SETTINGS = new SliceSettings(Duration.ofMinutes(3), Duration.ofMinutes(1),
		Duration.ofSeconds(1));
	/**
	 * The slices' deadline.
	 */
	private static final Instant DEADLINE = DriverHarness.START.plus(SETTINGS.budget());
	/**
	 * The end of the slices' grace period.
	 */
	private static final Instant GRACE_END = DEADLINE.plus(SETTINGS.grace());
	/**
	 * How long a test waits for the runner's thread.
	 */
	private static final long WAIT_SECONDS = 10;

	/**
	 * Creates the test.
	 */
	public SliceTest()
	{
	}

	/**
	 * Returns the fixture contract with the same time limit on every test.
	 *
	 * @param timeLimit the time limit
	 * @return the contract
	 */
	private static Contract withTimeLimit(Duration timeLimit)
	{
		Contract contract = ContractFixtures.contract();
		List<Category> categories = new ArrayList<>();
		for (Category category : contract.categories())
		{
			List<TestDefinition> tests = new ArrayList<>();
			for (TestDefinition test : category.tests())
			{
				tests.add(new TestDefinition(test.id(), test.requirementIds(), test.absentTriggers(), test.applicability(),
					test.turns(),
					timeLimit, test.expectedBehaviour(), test.uncoveredInteractions(), test.variations(), List.of(),
					test.checks(), test.discardedChecks(), test.regressionScenarios()));
			}
			categories.add(new Category(category.id(), category.rationale(), category.riskRank(), tests));
		}
		return new Contract(contract.requirements(), categories,
			ContractFixtures.profiles(contract.requirements(), categories), contract.judge(), contract.fixtures(),
			contract.limitations(), contract.salt());
	}

	/**
	 * Returns a harness whose campaign develops its working candidate under {@link #SETTINGS}.
	 *
	 * @param cleanup the test's temporary directories
	 * @return the harness
	 * @throws IOException if the test directory cannot be created
	 */
	private static DriverHarness developing(TemporaryDirectories cleanup) throws IOException
	{
		DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("slice")),
			new Builder().confirmed(withTimeLimit(TIME_LIMIT), 1).working(1).readyBaseline().
				development("formatting", 50, 0).development("safety", 50, 0).build());
		harness.settings(SETTINGS);
		return harness;
	}

	/**
	 * Returns the number of trial sessions that started.
	 *
	 * @param harness the harness
	 * @return the number of sessions
	 */
	private static int trialSessions(DriverHarness harness)
	{
		int count = 0;
		for (FakeSession session : harness.sessions().sessions())
		{
			if (session.request().role() == SessionRole.TRIAL)
				++count;
		}
		return count;
	}

	/**
	 * Returns the keys of the persisted trials in a state.
	 *
	 * @param campaign the campaign
	 * @param state the state
	 * @return the keys, in assignment order
	 */
	private static List<TrialKey> keys(Campaign campaign, TrialState state)
	{
		return campaign.trials().stream().filter(trial -> trial.state() == state).map(LogicalTrial::key).toList();
	}

	/**
	 * Returns how far into the slice the first trial ends, and whether another trial is admitted then. Until five
	 * trial durations are measured, a trial is admitted only if its time limit ends by the slice's deadline.
	 *
	 * @return the cases
	 */
	@DataProvider
	public static Object[][] cutoff()
	{
		return new Object[][]
			{
				{Duration.ofSeconds(60), true},
				{Duration.ofSeconds(61), false}
			};
	}

	/**
	 * Verifies that, before the slice has measured how long trials take, a trial is admitted only if its time limit
	 * ends by the slice's deadline, and that a slice that stops at the cutoff reports that the host should run again.
	 *
	 * @param elapsed how far into the slice the first trial ends
	 * @param admitted whether another trial is admitted then
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "cutoff")
	public void timeLimitCutsOffAdmission(Duration elapsed, boolean admitted) throws IOException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = developing(cleanup);
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(trialSessions(harness), "trialSessions").isEqualTo(1);
				harness.clock().set(DriverHarness.START.plus(elapsed));
				harness.endSessions();
				harness.settle(driver);
				if (admitted)
				{
					requireThat(trialSessions(harness), "trialSessions").isGreaterThan(1);
					return;
				}
				SliceResult result = harness.runSlice(driver);
				requireThat(trialSessions(harness), "trialSessions").isEqualTo(1);
				requireThat(harness.campaign().trials(), "trials").isEmpty();
				requireThat(result.status().status(), "status").isEqualTo(new ReportStatus.Active(Status.RUNNING));
			}
		}
	}

	/**
	 * Verifies that once the slice has measured five trials, admission uses the 90th percentile of their durations
	 * instead of the time limit, so that short trials keep being admitted past the time limit's cutoff.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void measuredDurationsExtendAdmission() throws IOException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = developing(cleanup);
			try (Driver driver = harness.open())
			{
				driver.step();
				// Each trial ends 10 seconds after it starts
				for (int seconds = 10; seconds <= 60; seconds += 10)
				{
					harness.clock().set(DriverHarness.START.plusSeconds(seconds));
					harness.endSessions();
					harness.settle(driver);
				}
				int beforeCutoff = trialSessions(harness);
				requireThat(beforeCutoff, "beforeCutoff").isGreaterThanOrEqualTo(6);

				harness.clock().set(DriverHarness.START.plusSeconds(70));
				harness.endSessions();
				harness.settle(driver);
				requireThat(trialSessions(harness), "trialSessions").isGreaterThan(beforeCutoff);
			}
		}
	}

	/**
	 * Verifies that sessions that still run at the slice's deadline get the grace period to end, and that at its end
	 * they are interrupted and their trials return to the queue as the same logical trials.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void graceCancelsRemainder() throws IOException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = developing(cleanup);
			try (Driver driver = harness.open())
			{
				requireThat(driver.step(), "step").isEqualTo(new Step.Continue(DEADLINE));
				List<FakeSession> started = harness.runningTrials();
				requireThat(started, "started").isNotEmpty();
				List<TrialKey> running = keys(harness.campaign(), TrialState.RUNNING);

				harness.clock().set(DEADLINE);
				requireThat(driver.step(), "step").isEqualTo(new Step.Continue(GRACE_END));
				harness.clock().set(GRACE_END.minusSeconds(1));
				requireThat(driver.step(), "step").isEqualTo(new Step.Continue(GRACE_END));
				for (FakeSession session : started)
					requireThat(session.isInterruptRequested(), "isInterruptRequested").isFalse();

				harness.clock().set(GRACE_END);
				Step step = driver.step();
				requireThat(step, "step").isInstanceOf(Step.Stopped.class);
				for (FakeSession session : started)
					requireThat(session.isInterruptRequested(), "isInterruptRequested").isTrue();
				Campaign campaign = harness.campaign();
				requireThat(keys(campaign, TrialState.ASSIGNED), "assigned").isEqualTo(running);
				requireThat(keys(campaign, TrialState.RUNNING), "running").isEmpty();
				requireThat(((Step.Stopped) step).result().status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.RUNNING));
			}
		}
	}

	/**
	 * Verifies that completing a session wakes the runner and lets it return after the grace period ended.
	 * <p>
	 * The runner runs on its own thread. Its next timed wake is at the slice's 3-minute deadline; advancing the fixture
	 * clock does not wake that wait. Completing the session lets it return within {@value #WAIT_SECONDS} real seconds.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the test is interrupted
	 * @throws ExecutionException if the runner fails
	 * @throws TimeoutException if the runner does not start a session or return in time
	 */
	@Test
	public void runnerWakesWhenSessionEnds()
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = developing(cleanup);
			harness.settings(new SliceSettings(SETTINGS.budget(), SETTINGS.grace(), Duration.ofHours(1)));
			DriverContext context = harness.context();
			BlockingQueue<FakeSession> started = new LinkedBlockingQueue<>();
			SessionPort sessions = new QueueingSessions(harness.sessions(), started);
			DriverContext observed = new DriverContext(context.store(), sessions, context.workspace(),
				context.interrupts(), context.clock(), context.overlays(), context.judge(), context.triage(),
				context.author(), context.values(), context.deliverer(), context.calibrator(), context.terminator(),
				context.leftoverRuns(), context.authority(), context.promptSources(), context.environment(),
				context.lockFile(), context.random(), context.settings());
			try (ExecutorService executor = Executors.newSingleThreadExecutor())
			{
				Future<SliceResult> slice = executor.submit(() ->
					SliceRunner.run(observed, DriverHarness.CAMPAIGN, List.of()));
				FakeSession first = started.poll(WAIT_SECONDS, TimeUnit.SECONDS);
				if (first == null)
					throw new TimeoutException("The runner did not start a session");
				requireThat(first.request().role(), "role").isEqualTo(SessionRole.TRIAL);

				harness.clock().set(GRACE_END);
				first.end(harness.passed());
				SliceResult result = slice.get(WAIT_SECONDS, TimeUnit.SECONDS);
				requireThat(result.status().status(), "status").isEqualTo(new ReportStatus.Active(Status.RUNNING));
				requireThat(keys(harness.campaign(), TrialState.ASSIGNED), "assigned").isNotEmpty();
				requireThat(keys(harness.campaign(), TrialState.RUNNING), "running").isEmpty();
			}
		}
	}

	/**
	 * Starts sessions through a fake port and queues each started session for the test's thread.
	 * <p>
	 * <b>Thread Safety:</b> This class is thread-safe.
	 */
	private static final class QueueingSessions implements SessionPort
	{
		private final FakeSessionPort delegate;
		private final BlockingQueue<FakeSession> started;

		/**
		 * Creates the port.
		 *
		 * @param delegate the port that starts the sessions
		 * @param started the queue that receives each started session
		 */
		QueueingSessions(FakeSessionPort delegate, BlockingQueue<FakeSession> started)
		{
			this.delegate = delegate;
			this.started = started;
		}

		@Override
		public String renderEvidence(SessionResult.Completed evidence, Path source)
		{
			return delegate.renderEvidence(evidence, source);
		}

		@Override
		public SessionHandle start(SessionRequest request)
		{
			FakeSession session = (FakeSession) delegate.start(request);
			started.add(session);
			return session;
		}

		@Override
		public List<Path> runRoots()
		{
			return delegate.runRoots();
		}

		@Override
		public HostLogin hostLogin()
		{
			return delegate.hostLogin();
		}

		@Override
		public Optional<String> findHeldOutExposure(Path project) throws IOException
		{
			return delegate.findHeldOutExposure(project);
		}
	}

	/**
	 * Verifies that the expected duration of a trial is its time limit until five trials were measured, then the
	 * 90th percentile of the measured durations.
	 */
	@Test
	public void expectedDuration()
	{
		// Requirement: R-E9
		SliceEvidence evidence = new SliceEvidence();
		for (int seconds = 1; seconds <= 4; ++seconds)
			evidence.recordDuration(Duration.ofSeconds(seconds));
		requireThat(evidence.expectedDuration(TIME_LIMIT), "expectedDuration").isEqualTo(TIME_LIMIT);

		evidence.recordDuration(Duration.ofSeconds(50));
		requireThat(evidence.expectedDuration(TIME_LIMIT), "expectedDuration").isEqualTo(Duration.ofSeconds(50));

		for (int seconds = 9; seconds >= 5; --seconds)
			evidence.recordDuration(Duration.ofSeconds(seconds));
		// Ten durations: 1..9 and 50 seconds; the 90th percentile is the ninth smallest
		requireThat(evidence.expectedDuration(TIME_LIMIT), "expectedDuration").isEqualTo(Duration.ofSeconds(9));
	}
}
