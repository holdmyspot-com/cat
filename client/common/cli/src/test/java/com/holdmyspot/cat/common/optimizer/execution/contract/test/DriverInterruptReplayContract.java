package com.holdmyspot.cat.common.optimizer.execution.contract.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.test.Boundaries;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.statistics.ComparisonBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.SliceResult;
import com.holdmyspot.cat.common.optimizer.execution.Step;
import com.holdmyspot.cat.common.optimizer.execution.test.DriverHarness;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;

import com.holdmyspot.cat.common.runner.AuthenticationEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Composes a recorded session adapter with {@link Driver} to verify campaign recovery after a host interruption.
 */
public final class DriverInterruptReplayContract
{
	/**
	 * Prevents instantiation.
	 */
	private DriverInterruptReplayContract()
	{
	}

	/**
	 * Replays one adapter interruption through a real driver, reopens the campaign, and completes every interrupted
	 * logical trial under its retained key through the scripted session port.
	 *
	 * @param interruptedSessions the harness's production adapter replaying its recorded interruption
	 * @param replayGate          waits until the recorded turn is in progress
	 * @throws IOException          if the campaign cannot be opened or persisted
	 * @throws InterruptedException if the test thread is interrupted while waiting for the recording
	 * @throws ExecutionException   if the production adapter cannot classify the recorded turn
	 * @throws TimeoutException     if the adapter does not classify the turn within 30 seconds
	 */
	public static void replaysInterruptedAttempt(SessionPort interruptedSessions, ReplayGate replayGate)
		throws IOException, InterruptedException, ExecutionException, TimeoutException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("driver-interrupt-replay-"));
			Campaign initial = new Builder().confirmed().working(1).readyBaseline().development("formatting", 50, 0).
				development("plain_list", 50, 0).development("safety", 50, 0).ceiling(4).certificationAttempt(1).
				boundaries(neutralBoundaries()).cell("formatting", 10, 0).cell("plain_list", 10, 0).cell("safety", 10, 0).
				campaign().window(4).build();
			DriverHarness harness = new DriverHarness(directory, initial);
			harness.trials(_ -> harness.passed());
			Map<String, CellCounts> countsBefore = attemptCounts(harness.campaign());
			int certificationIndex = harness.campaign().runningAttempt().orElseThrow().index();
			requireThat(certificationIndex, "initial_certification_index").isEqualTo(1);
			ReplayPort sessions = new ReplayPort(harness.sessions(), interruptedSessions);

			Set<TrialKey> interruptedKeys;
			Map<String, CellCounts> countsAtInterrupt;
			try (Driver driver = Driver.open(harness.context(sessions), DriverHarness.CAMPAIGN).orElseThrow())
			{
				Step start = driver.step();
				requireThat(start, "start").isInstanceOf(Step.Continue.class);
				replayGate.await();

				List<LogicalTrial> started = trials(harness.campaign(), TrialState.RUNNING);
				requireThat(started.size(), "started.size").isEqualTo(4);
				Set<TrialKey> startedKeys = keys(started);
				TrialKey recordedKey = started.getLast().key();
				requireThat(sessions.handle(), "recordedHandle").isNotNull();
				requireThat(harness.endSessions(), "scripted_sessions_ended").isEqualTo(3);
				harness.settle(driver);

				// Completed trials free slots that the driver refills; every trial still running must retain its key.
				Campaign beforeInterrupt = harness.campaign();
				interruptedKeys = keys(trials(beforeInterrupt, TrialState.RUNNING));
				requireThat(interruptedKeys, "recorded_assignment").contains(recordedKey);
				Set<TrialKey> completedKeys = new HashSet<>(startedKeys);
				completedKeys.remove(recordedKey);
				Map<String, CellCounts> expectedCounts = countsAfterPasses(countsBefore, completedKeys);
				countsAtInterrupt = attemptCounts(beforeInterrupt);
				requireThat(countsAtInterrupt, "completed_outcomes_by_cell").isEqualTo(expectedCounts);

				harness.clock().advance(Duration.ofSeconds(1));
				harness.interrupts().interrupt(harness.clock().instant());
				Step stopped = driver.step();
				requireThat(stopped, "stopped").isInstanceOf(Step.Stopped.class);
				SliceResult result = ((Step.Stopped) stopped).result();
				requireThat(result.status().outcome(), "outcome").isEqualTo("interrupted");
				SessionResult recordedResult = sessions.handle().completion().toCompletableFuture().
					get(30, TimeUnit.SECONDS);
				requireThat(recordedResult, "recorded_result").isInstanceOf(SessionResult.Interrupted.class);

				Campaign interrupted = harness.campaign();
				requireThat(trials(interrupted, TrialState.RUNNING), "running_after_interrupt").isEmpty();
				requireThat(attemptCounts(interrupted), "retained_outcomes").isEqualTo(countsAtInterrupt);
				requireThat(keys(trials(interrupted, TrialState.ASSIGNED)), "retained_assignments").
					isEqualTo(interruptedKeys);
				requireThat(interrupted.runningAttempt().orElseThrow().index(), "certification_index").
					isEqualTo(certificationIndex);
			}

			try (Driver driver = Driver.open(harness.context(), DriverHarness.CAMPAIGN).orElseThrow())
			{
				driver.step();
				Set<TrialKey> resumedKeys = keys(trials(harness.campaign(), TrialState.RUNNING));
				requireThat(resumedKeys, "resumed_assignments").isEqualTo(interruptedKeys);
				requireThat(attemptCounts(harness.campaign()), "outcomes_before_rerun_completes").
					isEqualTo(countsAtInterrupt);
				requireThat(harness.endSessions(), "resumed_sessions_ended").isEqualTo(resumedKeys.size());
				harness.settle(driver);

				Map<String, CellCounts> countsAfterResume = countsAfterPasses(countsAtInterrupt, resumedKeys);
				requireThat(attemptCounts(harness.campaign()), "outcomes_after_one_rerun_per_key").
					isEqualTo(countsAfterResume);
				requireThat(harness.campaign().runningAttempt().orElseThrow().index(), "certification_index").
					isEqualTo(certificationIndex);
			}
		}
	}

	/**
	 * Returns the trials in one execution state, preserving campaign order.
	 *
	 * @param campaign the campaign
	 * @param state the trial state
	 * @return the matching trials
	 */
	private static List<LogicalTrial> trials(Campaign campaign, TrialState state)
	{
		return campaign.trials().stream().filter(trial -> trial.state() == state).toList();
	}

	/**
	 * Returns the keys of the given logical trials.
	 *
	 * @param trials the logical trials
	 * @return their keys
	 */
	private static Set<TrialKey> keys(List<LogicalTrial> trials)
	{
		return trials.stream().map(LogicalTrial::key).collect(java.util.stream.Collectors.toSet());
	}

	/**
	 * Returns the running certification attempt's per-cell counts.
	 *
	 * @param campaign the campaign
	 * @return the counts
	 */
	private static Map<String, CellCounts> attemptCounts(Campaign campaign)
	{
		return campaign.runningAttempt().orElseThrow().counts();
	}

	/**
	 * Adds one passing outcome for each completed logical trial to its cell's counts.
	 *
	 * @param previousCounts the counts before the completed trials
	 * @param completedKeys the completed trial keys
	 * @return the expected counts
	 */
	private static Map<String, CellCounts> countsAfterPasses(Map<String, CellCounts> previousCounts,
		Set<TrialKey> completedKeys)
	{
		Map<String, CellCounts> updated = new HashMap<>(previousCounts);
		for (TrialKey key : completedKeys)
			updated.compute(key.cell(), (_, counts) ->
			{
				if (counts == null)
					return CellCounts.EMPTY.add(Verdict.PASS);
				return counts.add(Verdict.PASS);
			});
		return Map.copyOf(updated);
	}

	/**
	 * Returns the neutral certification boundaries used by the composed host-interrupt driver test.
	 *
	 * @return the boundaries
	 */
	private static DecisionBoundaries neutralBoundaries()
	{
		ComparisonBoundaries neutral = Boundaries.comparison(Boundaries.STANDARD_CAP, 1000, 0,
			Boundaries.STANDARD_CAP + 1);
		return Boundaries.decision(Boundaries.STANDARD_CAP, 100, 50, 5, List.of(neutral, neutral, neutral));
	}

	/**
	 * Waits until the recorded interrupted turn is in progress.
	 */
	@FunctionalInterface
	public interface ReplayGate
	{
		/**
		 * Waits until the recorded interrupted turn is in progress.
		 *
		 * @throws InterruptedException if the test thread is interrupted
		 */
		void await() throws InterruptedException;
	}

	/**
	 * Routes the last trial in the initial window to the recording and every other operation to the existing scripted
	 * port.
	 */
	private static final class ReplayPort implements SessionPort
	{
		private final SessionPort scripted;
		private final SessionPort recorded;
		private final AtomicInteger trialStarts = new AtomicInteger();
		private final AtomicBoolean recordedTrialStarted = new AtomicBoolean();
		private SessionHandle handle;

		/**
		 * Creates a session port that sends the fourth trial to the recording.
		 *
		 * @param scripted the existing scripted port for ordinary completions
		 * @param recorded the production adapter replaying the interrupted turn
		 */
		private ReplayPort(SessionPort scripted, SessionPort recorded)
		{
			this.scripted = scripted;
			this.recorded = recorded;
		}

		/**
		 * Returns the session handle for the recorded turn.
		 *
		 * @return the handle, or null before the recorded trial starts
		 */
		private SessionHandle handle()
		{
			return handle;
		}

		@Override
		public SessionHandle start(SessionRequest request) throws IOException
		{
			// Earlier trials in the same cell must enter evidence before the interrupted trial can be resumed.
			if (request.role() == com.holdmyspot.cat.common.optimizer.port.SessionRole.TRIAL &&
				trialStarts.incrementAndGet() == 4 && recordedTrialStarted.compareAndSet(false, true))
			{
				handle = recorded.start(request);
				return handle;
			}
			return scripted.start(request);
		}

		@Override
		public int sessionEnvironmentVersion()
		{
			return scripted.sessionEnvironmentVersion();
		}

		@Override
		public String sessionEnvironmentRelease()
		{
			return scripted.sessionEnvironmentRelease();
		}

		@Override
		public int judgeVersion()
		{
			return scripted.judgeVersion();
		}

		@Override
		public SessionEvidence readEvidence(Path transcript) throws IOException
		{
			return scripted.readEvidence(transcript);
		}

		@Override
		public boolean reportsExitStatus()
		{
			return scripted.reportsExitStatus();
		}

		@Override
		public Optional<AuthenticationEvidence> authentication() throws IOException
		{
			return scripted.authentication();
		}

		@Override
		public HostLogin hostLogin() throws IOException
		{
			return scripted.hostLogin();
		}

		@Override
		public List<String> unapprovedHooks(Path plugin) throws IOException
		{
			return scripted.unapprovedHooks(plugin);
		}

		@Override
		public Optional<String> findHeldOutExposure(Path project) throws IOException
		{
			return scripted.findHeldOutExposure(project);
		}

		@Override
		public LoadedSources loadedSources(Path project, List<PluginBase> plugins) throws IOException
		{
			return scripted.loadedSources(project, plugins);
		}

		@Override
		public List<HookRegistration> hookRegistrations(Path project, List<PluginBase> plugins) throws IOException
		{
			return scripted.hookRegistrations(project, plugins);
		}

		@Override
		public List<HookInvocation> earlyHooks(Path project, List<PluginBase> plugins, Optional<Path> home, String prompt)
			throws IOException
		{
			return scripted.earlyHooks(project, plugins, home, prompt);
		}

		@Override
		public List<Path> installedPlugins(String name) throws IOException
		{
			return scripted.installedPlugins(name);
		}

		@Override
		public List<String> installedPluginNames() throws IOException
		{
			return scripted.installedPluginNames();
		}

		@Override
		public List<Path> resultsFolders()
		{
			return scripted.resultsFolders();
		}
	}
}
