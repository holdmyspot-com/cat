package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.FailedRequirement;
import io.github.cowwoc.cat.common.optimizer.execution.fixture.FaultingFileChannel;
import io.github.cowwoc.cat.common.optimizer.campaign.Boundaries;
import io.github.cowwoc.cat.common.optimizer.campaign.AttemptResult;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.Builder;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.CellTier;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationAttempt;
import io.github.cowwoc.cat.common.optimizer.campaign.CertificationRecord;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.ConcurrencyPolicy;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable;
import io.github.cowwoc.cat.common.optimizer.campaign.Development;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable.CellDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.DiagnosticRerun;
import io.github.cowwoc.cat.common.optimizer.campaign.Failure;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.campaign.TriedCombination;
import io.github.cowwoc.cat.common.optimizer.campaign.ReproductionRound;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureGroup;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureSummary;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.Harness;
import io.github.cowwoc.cat.common.optimizer.campaign.Header;
import io.github.cowwoc.cat.common.optimizer.campaign.Impact;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.LogicalTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.ReadinessGate;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.SlowStart;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialState;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.AddedCombination;
import io.github.cowwoc.cat.common.optimizer.contract.AddedValues;
import io.github.cowwoc.cat.common.optimizer.contract.Category;
import io.github.cowwoc.cat.common.optimizer.contract.VariationChoice;
import io.github.cowwoc.cat.common.optimizer.contract.Part;
import io.github.cowwoc.cat.common.optimizer.contract.Cell;
import io.github.cowwoc.cat.common.optimizer.contract.CheckCriterion;
import io.github.cowwoc.cat.common.optimizer.contract.CheckResult;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.ControlEvidence;
import io.github.cowwoc.cat.common.optimizer.contract.DeterministicCheck;
import io.github.cowwoc.cat.common.optimizer.contract.Requirement;
import io.github.cowwoc.cat.common.optimizer.contract.Split;
import io.github.cowwoc.cat.common.optimizer.contract.JudgeProfile;
import io.github.cowwoc.cat.common.optimizer.contract.TestDefinition;
import io.github.cowwoc.cat.common.optimizer.contract.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.ToolCall;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.contract.TrialProfile;
import io.github.cowwoc.cat.common.optimizer.contract.UndecidableCheckException;
import io.github.cowwoc.cat.common.optimizer.contract.VariationValue;
import io.github.cowwoc.cat.common.optimizer.delivery.DeliveryResult;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryChange;
import io.github.cowwoc.cat.common.optimizer.inventory.DeliveryConflict;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Operation;
import io.github.cowwoc.cat.common.optimizer.inventory.PathChange;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.inventory.Overlay;
import io.github.cowwoc.cat.common.optimizer.inventory.OverlayEntry.Write;
import io.github.cowwoc.cat.common.optimizer.port.FakeSessionPort.FakeSession;
import io.github.cowwoc.cat.common.optimizer.port.HostLogin;
import io.github.cowwoc.cat.common.optimizer.port.RateLimitWindow;
import io.github.cowwoc.cat.common.optimizer.port.SessionHandle;
import io.github.cowwoc.cat.common.optimizer.port.SessionPort;
import io.github.cowwoc.cat.common.optimizer.port.SessionRequest;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.SessionRole;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.report.TokenStatistics;
import io.github.cowwoc.cat.common.optimizer.store.FileCampaignStore;
import io.github.cowwoc.cat.common.optimizer.store.TrialSession;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.ReportStatus;
import io.github.cowwoc.cat.common.optimizer.report.Status;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import io.github.cowwoc.cat.common.optimizer.report.CellReport;
import io.github.cowwoc.cat.common.optimizer.report.OptimizationReport.InputComparison;
import io.github.cowwoc.cat.common.optimizer.statistics.ComparisonBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Covers campaign scenarios from builder state, through the in-memory store, the
 * fake session and workspace ports and a manual clock.
 */
public final class DriverTest
{
	/**
	 * Completed candidate and incumbent trials record their observed profiles and report improvement and token medians.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-M1
	// Requirement: R-M4
	// Requirement: R-E18
	@Test
	public void statusComparesObservedTrials() throws IOException
	{
		Map<MeasurementCondition, String> conditions = new EnumMap<>(MeasurementCondition.class);
		conditions.putAll(Builder.confirmedIdentity(DriverHarness.MODEL).values());
		for (MeasurementCondition condition : List.of(MeasurementCondition.JUDGE, MeasurementCondition.MODEL,
			MeasurementCondition.REASONING, MeasurementCondition.HOST_TOOLS, MeasurementCondition.EXTERNAL_SOURCES))
		{
			conditions.remove(condition);
		}
		MeasurementIdentity initial = new MeasurementIdentity(conditions);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("observed-status")),
				new Builder().confirmed().identity(initial).baselineIdentity(initial).working(1).build());
			harness.trials(_ ->
			{
				SessionResult.Completed completed = harness.passed();
				return new SessionResult.Completed(completed.transcript(), completed.responses(), completed.effects(),
					completed.model(), completed.reasoning(), new TokenUsage(80, 0, 0, 0), completed.windows(),
					completed.promptSources(), completed.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state ->
					state.baseline().count("formatting").outcomes() > 0 &&
						state.baseline().count("safety").outcomes() > 0 &&
						state.development().count("formatting").outcomes() > 0 &&
						state.development().count("safety").outcomes() > 0);
				StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
				for (CellReport cell : report.cells())
				{
					requireThat(cell.improvement().unavailable(), "improvementUnavailable").isNull();
					requireThat(cell.improvement().points(), "improvement").isEqualTo(0.0);
				}
				for (InputComparison inputs : report.optimization().inputs())
				{
					requireThat(inputs.workingMedian(), "workingMedian").isEqualTo(80L);
					requireThat(inputs.incumbentMedian(), "incumbentMedian").isEqualTo(80L);
				}
				String judge = Builder.OBSERVED.values().get(MeasurementCondition.JUDGE);
				requireThat(campaign.identity().values().get(MeasurementCondition.JUDGE), "judge").isEqualTo(judge);
				requireThat(campaign.baseline().identity().values().get(MeasurementCondition.JUDGE), "baselineJudge").
					isEqualTo(judge);
				for (String cell : campaign.contract().cellIds())
				{
					requireThat(campaign.identity().cells().get(cell).get(MeasurementCondition.MODEL), "model").
						isEqualTo(DriverHarness.MODEL);
					requireThat(campaign.baseline().identity().cells().get(cell).get(MeasurementCondition.REASONING),
						"baselineReasoning").isEqualTo("reasoning-1");
				}
			}
		}
	}

	/**
	 * A changed trial model pauses after trials first record the same cell's observed conditions.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-M5
	@Test
	public void firstObservationDetectsChanges() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("measurement-change")),
				unobservedProfile());
			AtomicBoolean changed = new AtomicBoolean();
			harness.trials(_ ->
			{
				String model = DriverHarness.MODEL;
				if (changed.get())
					model = "changed-model";
				return harness.completed("pass", model);
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().count("formatting").outcomes() >= 50 &&
					state.development().count("safety").outcomes() >= 50);
				changed.set(true);
				Campaign campaign = harness.runUntil(driver,
					state -> state.pending() instanceof PendingDecision.MeasurementChanged);
				PendingDecision.MeasurementChanged pending = (PendingDecision.MeasurementChanged) campaign.pending();
				requireThat(pending.changed(), "changed").isEqualTo(List.of(MeasurementCondition.MODEL));
				requireThat(harness.store().listUnadoptedOutcomes(campaign), "unadoptedOutcomes").isEmpty();
			}
		}
	}

	/**
	 * Returns a campaign that has not yet observed its judge, trial model or reasoning configuration, with 49
	 * development outcomes in each cell.
	 *
	 * @return the campaign
	 */
	private static Campaign unobservedProfile()
	{
		Map<MeasurementCondition, String> conditions = new EnumMap<>(MeasurementCondition.class);
		conditions.putAll(Builder.confirmedIdentity(DriverHarness.MODEL).values());
		conditions.remove(MeasurementCondition.JUDGE);
		conditions.remove(MeasurementCondition.MODEL);
		conditions.remove(MeasurementCondition.REASONING);
		MeasurementIdentity initial = new MeasurementIdentity(conditions);
		return new Builder().confirmed().identity(initial).baselineIdentity(initial).working(1).readyBaseline().
			development("formatting", 49, 0).development("safety", 49, 0).ceiling(1).build();
	}

	/**
	 * The model that a judge session reports is not a measurement condition: the judge version stands for the
	 * judge, so a changed judge session model neither pauses the campaign nor is recorded.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-M5
	@Test
	public void judgeSessionModelIsNotRecorded() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("judge-model")),
				unobservedProfile());
			AtomicBoolean changed = new AtomicBoolean();
			harness.judges(request ->
			{
				SessionResult.Completed judgment = harness.scriptedJudgment(request);
				if (!changed.get())
					return judgment;
				return new SessionResult.Completed(judgment.transcript(), judgment.responses(), judgment.effects(),
					"changed-judge", judgment.reasoning(), judgment.usage(), judgment.windows(), judgment.promptSources(),
					judgment.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().count("formatting").outcomes() >= 50 &&
					state.development().count("safety").outcomes() >= 50);
				changed.set(true);
				Campaign campaign = harness.runUntil(driver, state ->
					state.development().count("formatting").outcomes() >= 51 &&
						state.development().count("safety").outcomes() >= 51);
				requireThat(campaign.pending(), "pending").isNull();
				requireThat(campaign.identity().values().get(MeasurementCondition.JUDGE), "judge").
					isEqualTo(Builder.OBSERVED.values().get(MeasurementCondition.JUDGE));
			}
		}
	}

	/**
	 * A new judge version between trials pauses the campaign before the next session starts.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-M5
	@Test
	public void judgeVersionChangePauses() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("judge-version")),
				developing().build());
			harness.observe(MeasurementCondition.JUDGE, "judge-2");
			try (Driver driver = harness.open())
			{
				harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.MeasurementChanged.class);
				requireThat(((PendingDecision.MeasurementChanged) campaign.pending()).changed(), "changed").
					isEqualTo(List.of(MeasurementCondition.JUDGE));
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
			}
		}
	}

	/**
	 * A CAT and runner version change between trials neither pauses the campaign nor replaces the recorded versions.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-M5
	@Test
	public void catVersionChangeDoesNotPause() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("version-change")),
				new Builder().confirmed().working(1).readyBaseline().development("formatting", 49, 0).
					development("safety", 49, 0).ceiling(1).build());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().count("formatting").outcomes() >= 50 &&
					state.development().count("safety").outcomes() >= 50);
			}
			harness.observe(MeasurementCondition.CAT_VERSION, "cat_version-2");
			harness.observe(MeasurementCondition.RUNNER_VERSION, "runner_version-2");
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state ->
					state.development().count("formatting").outcomes() >= 51 &&
						state.development().count("safety").outcomes() >= 51);
				requireThat(campaign.pending(), "pending").isNull();
				requireThat(campaign.identity().values().get(MeasurementCondition.CAT_VERSION), "catVersion").
					isEqualTo("cat_version-1");
			}
		}
	}

	/**
	 * Reloaded status retains trial, judge and author usage, including rejected author outputs.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-E12
	@Test
	public void statusRetainsFinishedSessionUsage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("session-usage"));
			DriverHarness harness = new DriverHarness(directory, authoring().baseline("formatting", 49, 0).build());
			FileCampaignStore store = new FileCampaignStore(directory, JsonMapper.builder().build());
			store.replaceSnapshot(harness.campaign());
			DriverContext original = harness.context();
			DriverContext context = new DriverContext(store, original.sessions(), original.workspace(),
				original.interrupts(), original.clock(), original.overlays(), original.judge(), original.triage(),
				original.author(), original.values(), original.deliverer(), original.calibrator(), original.terminator(),
				original.leftoverRuns(), original.authority(), original.promptSources(), original.environment(),
				original.lockFile(), original.random(), original.settings());
			Map<SessionRole, List<TokenUsage>> expected = new EnumMap<>(SessionRole.class);
			harness.trials(_ -> withUsage(harness.failed(), SessionRole.TRIAL, expected));
			AtomicInteger judgments = new AtomicInteger();
			harness.judges(request ->
			{
				if (judgments.getAndIncrement() == 0)
					return withUsage(harness.judged("{}"), SessionRole.JUDGE, expected);
				return withUsage(harness.scriptedJudgment(request), SessionRole.JUDGE, expected);
			});
			harness.authors(request -> withUsage((SessionResult.Completed) harness.edit(request, Path.of("README.md")),
				SessionRole.AUTHOR, expected));
			try (Driver driver = Driver.open(context, DriverHarness.CAMPAIGN).orElseThrow())
			{
				SliceResult result = harness.runSlice(driver);
				TokenStatistics reported = StatusReport.of(new FileCampaignStore(directory, JsonMapper.builder().build()).
					load(DriverHarness.CAMPAIGN).orElseThrow(), ReportEvidence.NONE).tokens();
				requireThat(reported, "durableUsage").isEqualTo(TokenStatistics.of(expected));
				requireThat(result.status().tokens(), "sliceUsage").isEqualTo(reported);
				for (SessionRole role : List.of(SessionRole.TRIAL, SessionRole.JUDGE, SessionRole.AUTHOR))
					requireThat(reported.roles().get(role).sessions(), "sessions").isPositive();
			}
		}
	}

	/**
	 * Sessions with absent usage contribute no statistics and report the unavailable reason.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-E12
	@Test
	public void statusOmitsAbsentSessionUsage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("absent-usage")),
				new Builder().confirmed().build());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.status().tokens().roles(), "roles").isEmpty();
				requireThat(result.status().tokens().unavailable(), "reason").isEqualTo("The harness reported no token usage.");
				requireThat(StatusReport.of(harness.campaign(), ReportEvidence.NONE).tokens(), "reloadedUsage").
					isEqualTo(result.status().tokens());
			}
		}
	}

	/**
	 * Missing usage in some trial sessions does not contribute zero-valued observations.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-E12
	@Test
	public void statusOmitsSomeSessionUsage() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("partial-usage")),
				new Builder().confirmed().ceiling(1).build());
			Map<SessionRole, List<TokenUsage>> expected = new EnumMap<>(SessionRole.class);
			AtomicInteger trials = new AtomicInteger();
			harness.trials(_ ->
			{
				SessionResult.Completed completed = harness.passed();
				if (trials.incrementAndGet() % 2 == 0)
					return completed;
				return withUsage(completed, SessionRole.TRIAL, expected);
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, campaign -> campaign.evidenceRevision() >= 4);
				harness.clock().advance(Duration.ofHours(1));
				TokenStatistics reported = harness.runSlice(driver).status().tokens();
				requireThat(reported, "partialUsage").isEqualTo(TokenStatistics.of(expected));
				requireThat(reported.roles().get(SessionRole.TRIAL).sessions(), "reportedTrials").
					isLessThan(trials.get());
				requireThat(reported.roles().containsKey(SessionRole.JUDGE), "absentJudgeUsage").isFalse();
			}
		}
	}

	/**
	 * Supplies distinct observed counts to the driver's completed session.
	 *
	 * @param completed the session result
	 * @param role the role
	 * @param expected the observations supplied to the driver
	 * @return the result with reported usage
	 */
	private static SessionResult.Completed withUsage(SessionResult.Completed completed, SessionRole role,
		Map<SessionRole, List<TokenUsage>> expected)
	{
		List<TokenUsage> sessions = expected.computeIfAbsent(role, _ -> new ArrayList<>());
		int count = sessions.size() + 1;
		TokenUsage usage = new TokenUsage(count * 100L, count * 20L, count * 30L, count * 10L);
		sessions.add(usage);
		return new SessionResult.Completed(completed.transcript(), completed.responses(), completed.effects(),
			completed.model(), completed.reasoning(), usage, completed.windows(), completed.promptSources(),
			completed.toolCalls());
	}

	/**
	 * Weak authors receive identical evidence after older development trials settle, including when capacity delays
	 * the third attempt.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void weakAuthorsWaitForDevelopment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("author-briefs")),
				new Builder().confirmed().working(1).readyBaseline().ceiling(2).
					development("safety", 10, 0).development("formatting", 10, 0).
					slowStart(new SlowStart(2, 0)).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "initialDevelopmentTrials").isEqualTo(2);
				FakeSession held = trials.get(1);
				trials.getFirst().end(harness.failed());
				harness.settle(driver);
				harness.runningTrials().stream().filter(session -> session != held).findFirst().orElseThrow().
					end(harness.failed());
				harness.settle(driver);
				requireThat(harness.campaign().development().round().weak(), "weakRound").isTrue();
				requireThat(harness.author().briefs(), "authorsBeforeDevelopmentSettles").isEmpty();

				held.end(harness.passed());
				harness.settle(driver);
				requireThat(harness.running().size(), "sharedCapacity").isLessThanOrEqualTo(2);
				harness.runUntil(driver, _ -> harness.author().briefs().size() == 3);
				for (AuthorBrief brief : harness.author().briefs())
				{
					requireThat(brief.developmentFailures(), "sameFailures").
						isEqualTo(harness.author().briefs().getFirst().developmentFailures());
					requireThat(brief.developmentCounts(), "sameCounts").
						isEqualTo(harness.author().briefs().getFirst().developmentCounts());
				}
				List<SessionRequest> authors = harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.AUTHOR).toList();
				requireThat(authors.get(0).turns().getFirst(), "firstApproach").
					contains("Correct the instruction where it is stated:");
				requireThat(authors.get(1).turns().getFirst(), "secondApproach").
					contains("Rewrite rather than add:");
				requireThat(authors.get(2).turns().getFirst(), "thirdApproach").
					contains("Add the missing guidance where the failing sessions make the decision,");
				requireThat(harness.campaign().development().counts().values().stream().
					mapToLong(CellCounts::passes).sum(), "settledDevelopmentPasses").isEqualTo(21);
			}
		}
	}

	/**
	 * A blocking development failure discards the working candidate's other development trial, interrupting its
	 * session without recording a failure, and starts the round's authors at once, without diagnostic reruns and
	 * without waiting for the discarded trial.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void blockingFailureFailsFast() throws IOException
	{
		// Requirement: R-D16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("fail-fast")),
				new Builder().confirmed().working(1).readyBaseline().ceiling(2).
					development("safety", 10, 0).development("formatting", 10, 0).
					slowStart(new SlowStart(2, 0)).build());
			harness.triages(_ -> harness.triaged(Impact.BLOCKING));
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "developmentTrials").isEqualTo(2);
				FakeSession held = trials.get(1);
				trials.getFirst().end(harness.failed());
				harness.settle(driver);

				requireThat(held.isInterruptRequested(), "heldInterrupted").isTrue();
				Campaign campaign = harness.campaign();
				requireThat(campaign.trials(), "trials").isEmpty();
				Failure failure = campaign.development().failures().getFirst();
				requireThat(failure.triage().impact(), "impact").isEqualTo(Impact.BLOCKING);
				requireThat(failure.reruns(), "reruns").isEmpty();
				requireThat(campaign.development().diagnosis(), "diagnosis").isNull();
				requireThat(campaign.development().round().weak(), "weakRound").isTrue();
				requireThat(harness.author().briefs(), "authors").isNotEmpty();
				requireThat(harness.author().briefs().getFirst().developmentFailures(), "briefFailures").
					isEqualTo(List.of(failure));
				long trialSessions = harness.sessions().sessions().stream().
					filter(session -> session.request().role() == SessionRole.TRIAL).count();
				requireThat(trialSessions, "trialSessions").isEqualTo(2L);
			}
		}
	}

	/**
	 * A triage whose output never matches its schema reruns with the same evidence, and its third invalid output
	 * records the failure as blocking.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void invalidTriageCountsAsBlocking() throws IOException
	{
		// Requirement: R-D16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("invalid-triage")),
				developing().slowStart(new SlowStart(1, 0)).build());
			AtomicBoolean failedOnce = new AtomicBoolean();
			harness.trials(request ->
			{
				if (DriverHarness.cell(request).equals("safety") && failedOnce.compareAndSet(false, true))
					return harness.failed();
				return harness.passed();
			});
			harness.triages(_ -> harness.triaged("blocking"));
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> !state.development().failures().isEmpty());
				List<SessionRequest> triages = triageRequests(harness);
				requireThat(triages.size(), "triages").isEqualTo(Driver.TRIAGE_ATTEMPTS);
				for (SessionRequest triage : triages)
					requireThat(triage, "triage").isEqualTo(triages.getFirst());
				Failure failure = campaign.development().failures().getFirst();
				requireThat(failure.triage().impact(), "impact").isEqualTo(Impact.BLOCKING);
				requireThat(failure.triage().rationale(), "rationale").
					startsWith("The triage failed " + Driver.TRIAGE_ATTEMPTS + " times");
				requireThat(failure.rationale(), "failureRationale").
					isEqualTo("The trial did not meet the requirements.");
				requireThat(campaign.development().count("safety").failures(), "failures").isEqualTo(1);
				requireThat(campaign.development().round(), "round").isNotNull();
			}
		}
	}

	/**
	 * A rate-limit rejection of a triage session holds admissions until the provider's reset and then reruns the
	 * triage with the same evidence, without counting the rejection as a failure of the triage.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void triageRateLimit() throws IOException
	{
		// Requirement: R-D16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("triage-limit")),
				developing().slowStart(new SlowStart(1, 0)).build());
			harness.trials(_ -> harness.failed());
			AtomicInteger rejections = new AtomicInteger(Driver.TRIAGE_ATTEMPTS);
			AtomicReference<Instant> resetsAt = new AtomicReference<>();
			harness.triages(_ ->
			{
				if (rejections.getAndDecrement() > 0)
					return new SessionResult.RateLimited(resetsAt.get(), List.of());
				return harness.triaged(Impact.SELF_RECOVERING);
			});
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.endSessions(), "trials").isEqualTo(1);
				for (int rejection = 1; rejection <= Driver.TRIAGE_ATTEMPTS; ++rejection)
				{
					Instant reset = harness.clock().instant().plus(Duration.ofMinutes(2));
					resetsAt.set(reset);
					harness.settle(driver);
					requireThat(triageRequests(harness).size(), "triages").isEqualTo(rejection);
					requireThat(harness.campaign().throttle().holdUntil(), "holdUntil").isEqualTo(reset);
					harness.clock().set(reset);
				}
				harness.settle(driver);
				List<SessionRequest> triages = triageRequests(harness);
				requireThat(triages.size(), "triages").isEqualTo(Driver.TRIAGE_ATTEMPTS + 1);
				for (SessionRequest triage : triages)
					requireThat(triage, "triage").isEqualTo(triages.getFirst());
				Failure failure = harness.campaign().development().failures().getFirst();
				requireThat(failure.triage().impact(), "impact").isEqualTo(Impact.SELF_RECOVERING);
			}
		}
	}

	/**
	 * A weak round compares three independent attempts, minimizes only the best accepted edit, retains the other
	 * evidence, and returns to one author at a time after the new candidate's development estimate improves.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void weakRoundSelectsBestThenRunsSequentially() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("concurrent-authors")),
				new Builder().confirmed().working(1).readyBaseline().
					regression(Builder.targeted("formatting", "formal"), Verdict.FAIL).build());
			Sha256 working = Builder.snapshot("candidate-1");
			harness.overlays().put(working, harness.candidate("first\nseparator\nlast\n"));
			harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
			AtomicInteger authored = new AtomicInteger();
			harness.authors(request ->
			{
				int attempt = authored.incrementAndGet();
				return harness.edit(request, DriverHarness.PROMPT, switch (attempt)
				{
					case 1 -> "needed-one\nseparator\nextra-one\n";
					case 2 -> "needed-two\nseparator\nextra-two\n";
					default -> "bad\nseparator\nextra-three\n";
				});
			});
			harness.trials(request ->
			{
				try
				{
					Overlay overlay = harness.overlays().require(harness.snapshot(request));
					Write entry = (Write) overlay.entries().get(DriverHarness.PROMPT);
					String text = new String(entry.content().bytes(), UTF_8);
					if (DriverHarness.cell(request).equals("formatting"))
					{
						if (text.contains("needed-two"))
							return harness.passed();
						return harness.failed();
					}
					if (text.contains("needed-one") || text.contains("needed-two"))
						return harness.passed();
					return harness.failed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});

			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().round() != null);
				requireThat(harness.author().briefs().size(), "concurrentAttempts").isEqualTo(3);
				requireThat(harness.author().briefs().get(1).developmentFailures(), "sameFailures").
					isEqualTo(harness.author().briefs().get(0).developmentFailures());
				requireThat(harness.author().briefs().get(2).developmentCounts(), "sameCounts").
					isEqualTo(harness.author().briefs().get(0).developmentCounts());
				requireThat(harness.running().stream().filter(session ->
					session.request().role() == SessionRole.AUTHOR).count(), "runningAuthors").isEqualTo(3);

				Campaign promoted = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Overlay minimal = harness.overlays().require(promoted.development().working().snapshot());
				Write selected = (Write) minimal.entries().get(DriverHarness.PROMPT);
				requireThat(new String(selected.content().bytes(), UTF_8), "minimalWinner").
					isEqualTo("needed-two\nseparator\nlast\n");
				requireThat(promoted.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				requireThat(promoted.development().counts(), "newCounts").isEmpty();
				requireThat(promoted.attempts(), "allowance").isEmpty();
				ObjectNode status = StatusReport.of(promoted, ReportEvidence.NONE).toJson(JsonMapper.builder().build());
				JsonNode attempts = status.get("author_attempts");
				requireThat(attempts.size(), "retainedAttempts").isEqualTo(3);
				requireThat(attempts.get(0).get("passing_runs").asInt(), "firstPasses").isEqualTo(2);
				requireThat(attempts.get(0).get("accepted").asBoolean(), "firstAccepted").isTrue();
				requireThat(attempts.get(0).get("winner").asBoolean(), "firstWinner").isFalse();
				requireThat(attempts.get(1).get("passing_runs").asInt(), "secondPasses").isEqualTo(3);
				requireThat(attempts.get(1).get("accepted").asBoolean(), "secondAccepted").isTrue();
				requireThat(attempts.get(1).get("winner").asBoolean(), "secondWinner").isTrue();
				requireThat(attempts.get(2).get("accepted").asBoolean(), "thirdAccepted").isFalse();
				requireThat(attempts.get(2).get("winner").asBoolean(), "thirdWinner").isFalse();
				for (JsonNode attempt : attempts)
				{
					requireThat(attempt.get("total_runs").asInt(), "totalPromotionRuns").isEqualTo(3);
					harness.overlays().require(new Sha256(attempt.get("snapshot").asString()));
				}

				harness.trials(_ -> harness.passed());
				harness.runUntil(driver, state -> state.development().count("safety").passes() >= 80 &&
					state.development().count("formatting").passes() >= 80);
				harness.trials(request ->
				{
					if (DriverHarness.cell(request).equals("safety"))
						return harness.failed();
					return harness.passed();
				});
				harness.runUntil(driver, state -> state.development().round() != null);
				requireThat(harness.author().briefs().size(), "nextRoundAttempts").isEqualTo(4);
				requireThat(harness.running().stream().filter(session ->
					session.request().role() == SessionRole.AUTHOR).count(), "strongRoundAuthors").isEqualTo(1);
			}
		}
	}


	/**
	 * Restarting a weak round reruns unfinished targeted trials under their original identities and retains completed
	 * attempts without spending certification allowance or adding development evidence.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void weakRoundRestartsTargetedRuns() throws IOException
	{
		// Requirement: R-D12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("restart-authors")),
				new Builder().confirmed().working(1).readyBaseline().build());
			harness.trials(_ -> harness.failed());
			List<TrialKey> pending;
			Map<String, CellCounts> counts;
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().round() != null);
				requireThat(harness.author().briefs().size(), "attempts").isEqualTo(3);
				harness.endSessions();
				driver.step();
				Campaign saved = harness.campaign();
				pending = saved.trials().stream().filter(trial -> trial.key().purpose() == Purpose.PROMOTION).
					map(LogicalTrial::key).toList();
				requireThat(pending, "targetedTrials").isNotEmpty();
				counts = saved.development().counts();
			}

			harness.trials(_ -> harness.passed());
			try (Driver driver = harness.open())
			{
				driver.step();
				Campaign resumed = harness.campaign();
				requireThat(resumed.trials().stream().filter(trial -> trial.key().purpose() == Purpose.PROMOTION).
					map(LogicalTrial::key).toList(), "sameTrialIdentities").containsAll(pending);
				requireThat(resumed.development().counts(), "countsAfterRestart").isEqualTo(counts);
				Campaign promoted = harness.runUntil(driver, state -> state.development().working().number() == 2);
				requireThat(promoted.authorAttempts(), "attempts").isEqualTo(3);
				requireThat(promoted.development().counts(), "newCandidateCounts").isEmpty();
				requireThat(promoted.attempts(), "allowance").isEmpty();
				requireThat(harness.author().briefs().size(), "noRepeatedAuthors").isEqualTo(3);
			}
		}
	}

	/**
	 * All three weak attempts use one working reference's verdict, and that run does not count as their own evidence.
	 *
	 * @param referenceVerdict the working candidate's verdict on the regression instance
	 * @param accepted whether every attempt fixes the failure without introducing a regression
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D12
	@Test(dataProvider = "workingReferenceVerdicts")
	public void weakRoundSharesWorkingReference(Verdict referenceVerdict, boolean accepted) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = workingReferenceHarness(
				cleanup.add(Files.createTempDirectory("shared-reference")), referenceVerdict);
			try (Driver driver = harness.open())
			{
				Campaign planned = harness.runUntil(driver, state ->
					state.development().round().validationAttempts().size() == 3);
				requireThat(planned.development().round().validationAttempts().stream().
					flatMap(attempt -> attempt.runs().stream()).
					filter(run -> run.role() == PromotionRun.Role.WORKING_REFERENCE).count(), "referencePlans").
					isEqualTo(1);
				requireThat(harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.TRIAL &&
						harness.snapshot(request).equals(Builder.snapshot("candidate-1")) &&
						DriverHarness.cell(request).equals("formatting")).count(), "referenceSessions").isEqualTo(1);
				TrialKey reference = planned.development().round().validationAttempts().stream().
					flatMap(attempt -> attempt.runs().stream()).
					filter(run -> run.role() == PromotionRun.Role.WORKING_REFERENCE).
					map(PromotionRun::trial).findFirst().orElseThrow();

				Campaign decided = harness.runUntil(driver, state ->
				{
					if (state.development().author() != null)
					{
						for (PromotionRun run : state.development().author().runs())
						{
							if (run.role() == PromotionRun.Role.WORKING_REFERENCE)
								requireThat(run.trial(), "reductionReferenceIdentity").isEqualTo(reference);
						}
					}
					return state.development().round() == null;
				});
				requireThat(decided.optimization().attempts().size(), "decidedAttempts").isEqualTo(3);
				for (AttemptResult attempt : decided.optimization().attempts())
				{
					requireThat(attempt.accepted(), "sharedReferenceDecision").isEqualTo(accepted);
					requireThat(attempt.passingRuns(), "ownPassingRuns").isEqualTo(2);
					requireThat(attempt.totalRuns(), "ownPlannedRuns").isEqualTo(3);
				}
				requireThat(decided.attempts(), "certificationAllowance").isEmpty();
			}
		}
	}

	/**
	 * Returns the reference verdicts that reject or accept the same three failed regression runs.
	 *
	 * @return the reference verdict and expected acceptance for all attempts
	 */
	@DataProvider
	public static Object[][] workingReferenceVerdicts()
	{
		return new Object[][] {{Verdict.PASS, false}, {Verdict.FAIL, true}};
	}

	/**
	 * A restart resumes the weak round's unfinished working reference once under the same logical identity.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D12
	@Test
	public void weakRoundResumesOneWorkingReference() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = workingReferenceHarness(
				cleanup.add(Files.createTempDirectory("resume-reference")), Verdict.PASS);
			TrialKey reference;
			try (Driver driver = harness.open())
			{
				Campaign saved = harness.runUntil(driver, state ->
					state.development().round().validationAttempts().size() == 3 && state.trials().stream().
						anyMatch(trial -> trial.key().purpose() == Purpose.PROMOTION &&
							trial.key().snapshot().equals(Builder.snapshot("candidate-1")) &&
							trial.state() == TrialState.RUNNING));
				reference = saved.trials().stream().filter(trial -> trial.key().purpose() == Purpose.PROMOTION &&
					trial.key().snapshot().equals(Builder.snapshot("candidate-1"))).
					map(LogicalTrial::key).findFirst().orElseThrow();
			}

			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.campaign().trials().stream().
					filter(trial -> trial.key().purpose() == Purpose.PROMOTION &&
						trial.key().snapshot().equals(Builder.snapshot("candidate-1"))).
					map(LogicalTrial::key).toList(), "resumedReferenceIdentity").isEqualTo(List.of(reference));
				requireThat(harness.runningTrials().stream().filter(session ->
					harness.snapshot(session.request()).equals(Builder.snapshot("candidate-1"))).count(),
					"runningReferenceSessions").isEqualTo(1);
				requireThat(harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.TRIAL &&
						harness.snapshot(request).equals(Builder.snapshot("candidate-1"))).count(),
					"referenceStartsIncludingResume").isEqualTo(2);

				Campaign decided = harness.runUntil(driver, state -> state.development().round() == null);
				requireThat(decided.optimization().attempts().size(), "decidedAttempts").isEqualTo(3);
				for (AttemptResult attempt : decided.optimization().attempts())
				{
					requireThat(attempt.accepted(), "sharedReferenceDecision").isFalse();
					requireThat(attempt.passingRuns(), "ownPassingRuns").isEqualTo(2);
					requireThat(attempt.totalRuns(), "ownPlannedRuns").isEqualTo(3);
				}
				requireThat(harness.author().briefs().size(), "unchangedAuthorAttempts").isEqualTo(3);
			}
		}
	}

	/**
	 * Creates a weak round whose three edits fix its triggering failure but fail an instance with no working outcome.
	 *
	 * @param directory the fixture directory owned by the calling test
	 * @param referenceVerdict the working candidate's verdict on the failed regression instance
	 * @return the harness with scripted trial outcomes
	 * @throws IOException if the fixture cannot be used
	 */
	private static DriverHarness workingReferenceHarness(Path directory, Verdict referenceVerdict) throws IOException
	{
		Failure failure = new Failure(Builder.targeted("safety", "logs"), "representative failure", List.of(), null,
			List.of(new DiagnosticRerun(Verdict.FAIL, "reproduced failure")), List.of());
		DriverHarness harness = new DriverHarness(directory,
			new Builder().confirmed().working(1).readyBaseline().ceiling(16).slowStart(new SlowStart(16, 0)).
				round(new Round(Map.of("safety", 0), true, Map.of(), List.of())).failure(failure).
				regression(Builder.targeted("formatting", "formal"), null).build());
		harness.trials(request ->
		{
			if (DriverHarness.cell(request).equals("formatting"))
			{
				if (harness.snapshot(request).equals(Builder.snapshot("candidate-1")) && referenceVerdict == Verdict.PASS)
					return harness.passed();
				return harness.failed();
			}
			return harness.passed();
		});
		return harness;
	}


	/**
	 * The second sequential reservation uses the rewrite approach, including when its author session is resumed.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D14
	// Requirement: R-D3
	@Test
	public void sequentialApproachSurvivesRestart() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("approach-resume")),
				authoring().build());
			harness.trials(_ -> harness.failed());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> harness.author().briefs().size() == 2);
				SessionRequest second = harness.sessions().sessions().getLast().request();
				requireThat(second.role(), "role").isEqualTo(SessionRole.AUTHOR);
				requireThat(second.turns().getFirst(), "secondApproach").contains("Rewrite rather than add:");
			}

			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.author().briefs().size(), "resumedAuthor").isEqualTo(3);
				SessionRequest resumed = harness.sessions().sessions().getLast().request();
				requireThat(resumed.role(), "role").isEqualTo(SessionRole.AUTHOR);
				requireThat(resumed.turns().getFirst(), "resumedApproach").contains("Rewrite rather than add:");
				requireThat(harness.campaign().development().round().reservations().size(), "reservations").
					isEqualTo(2);
			}
		}
	}

	/**
	 * An attempt fixing one of two triggering instances becomes the working candidate after unnecessary hunks
	 * are removed, and its original partial promotion result is reported as accepted.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D11
	// Requirement: R-D3
	@Test
	public void partialFixBecomesWorkingCandidate() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("partial-fix")),
				new Builder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(Builder.targeted("safety", "logs")).failure(Builder.targeted("safety", "cache")).build());
			Sha256 working = Builder.snapshot("candidate-1");
			harness.overlays().put(working, harness.candidate("first\nseparator\nlast\n"));
			harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
			harness.authors(request -> harness.edit(request, DriverHarness.PROMPT, "needed\nseparator\nunneeded\n"));
			harness.trials(request ->
			{
				if (String.join("\n", request.turns()).contains("cache"))
					return harness.failed();
				try
				{
					Write entry = (Write) harness.overlays().require(harness.snapshot(request)).entries().
						get(DriverHarness.PROMPT);
					if (entry != null && new String(entry.content().bytes(), UTF_8).lines().
						anyMatch(line -> line.equals("needed")))
						return harness.passed();
					return harness.failed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});

			try (Driver driver = harness.open())
			{
				Campaign result = harness.runUntil(driver, state -> state.development().working().number() == 2 ||
					state.pending() instanceof PendingDecision.PlateauReached);
				requireThat(result.development().working().number(), "workingCandidate").isEqualTo(2);
				Write entry = (Write) harness.overlays().require(result.development().working().snapshot()).
					entries().get(DriverHarness.PROMPT);
				requireThat(new String(entry.content().bytes(), UTF_8), "minimalPrompt").
					isEqualTo("needed\nseparator\nlast\n");
				requireThat(result.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				JsonNode attempts = StatusReport.of(result, ReportEvidence.NONE).toJson(JsonMapper.builder().build()).
					get("author_attempts");
				requireThat(attempts.get(0).get("accepted").asBoolean(), "accepted").isTrue();
				requireThat(attempts.get(0).get("passing_runs").asInt(), "passingRuns").isEqualTo(3);
				requireThat(attempts.get(0).get("total_runs").asInt(), "totalRuns").isEqualTo(5);
			}
		}
	}

	/**
	 * A driver restart retains two passing outcomes and reruns the assigned third promotion run under the same key.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void extendedPromotionResumes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Failure failure = new Failure(Builder.targeted("safety", "logs"), "representative failure", List.of(), null,
				List.of(new DiagnosticRerun(Verdict.PASS, "pass"), new DiagnosticRerun(Verdict.FAIL, "fail"),
					new DiagnosticRerun(Verdict.FAIL, "fail")), List.of());
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("extended-resume")),
				new Builder().confirmed().working(1).readyBaseline().ceiling(1).development("safety", 60, 1).
					round(new Round(Map.of("safety", 0))).failure(failure).build());
			TrialKey extension;
			try (Driver driver = harness.open())
			{
				Campaign saved = harness.runUntil(driver, state -> state.development().author() != null &&
					state.development().author().runs().size() == 3 &&
					state.development().author().runs().getLast().trial() != null);
				extension = saved.development().author().runs().getLast().trial();
				requireThat(extension.purpose(), "purpose").isEqualTo(Purpose.PROMOTION);
				requireThat(saved.development().author().runs().stream().filter(run -> run.verdict() == Verdict.PASS).
					count(), "retainedPasses").isEqualTo(2);
			}

			try (Driver driver = harness.open())
			{
				driver.step();
				Campaign resumed = harness.campaign();
				requireThat(resumed.trials().stream().map(LogicalTrial::key).toList(), "resumedKey").contains(extension);
				requireThat(resumed.development().count("safety"), "unchangedCounts").isEqualTo(Builder.counts(60, 1));
				Campaign accepted = harness.runUntil(driver, state -> state.development().author() != null &&
					!state.development().author().minimality().acceptedRuns().isEmpty());
				List<PromotionRun> runs = accepted.development().author().minimality().acceptedRuns();
				requireThat(runs.size(), "acceptedStreak").isEqualTo(3);
				requireThat(runs.getLast().trial(), "completedExtension").isEqualTo(extension);
				requireThat(runs.getLast().verdict(), "extensionVerdict").isEqualTo(Verdict.PASS);
				requireThat(accepted.attempts(), "allowance").isEmpty();
			}
		}
	}

	/**
	 * A partial fix is withdrawn after its representative estimate falls below its predecessor, restoring the
	 * predecessor's counts and worktree and pausing the unproductive round at a plateau.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D15
	@Test
	public void partialFixRevertsAtPlateau() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("partial-revert")),
				new Builder().confirmed().working(1).readyBaseline().ceiling(1).
					development("safety", 7, 3).round(new Round(Map.of("safety", 1))).
					failure(Builder.targeted("safety", "logs")).failure(Builder.targeted("safety", "cache")).build());
			Candidate predecessor = harness.campaign().development().working();
			Map<String, CellCounts> counts = harness.campaign().development().counts();
			AtomicInteger authors = new AtomicInteger();
			harness.authors(request -> harness.edit(request, DriverHarness.PROMPT,
				"needed-" + authors.incrementAndGet()));
			harness.trials(request ->
			{
				try
				{
					if (harness.campaign().development().working().number() == 2)
					{
						if (harness.campaign().development().diagnosis() != null)
							return harness.passed();
						return harness.failed();
					}
					if (String.join("\n", request.turns()).contains("cache"))
						return harness.failed();
					Write entry = (Write) harness.overlays().require(harness.snapshot(request)).entries().
						get(DriverHarness.PROMPT);
					if (entry != null && new String(entry.content().bytes(), UTF_8).startsWith("needed-"))
						return harness.passed();
					return harness.failed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});

			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().working().number() == 2);
				Campaign reverted = harness.runUntil(driver,
					state -> state.pending() instanceof PendingDecision.PlateauReached);

				requireThat(reverted.development().working(), "restoredCandidate").isEqualTo(predecessor);
				requireThat(reverted.development().counts(), "restoredCounts").isEqualTo(counts);
				requireThat(harness.workspace().scanWorktree(), "restoredWorktree").
					isEqualTo(harness.overlays().require(predecessor.snapshot()).snapshot());
				requireThat(harness.author().briefs().size(), "authorsBeforePause").isEqualTo(1);
				requireThat(reverted.outcome(), "outcome").isEqualTo(Outcome.PLATEAU);
				requireThat(StatusReport.of(reverted, ReportEvidence.NONE).status().label(), "status").
					isEqualTo("awaiting_user");
			}
		}
	}


	/**
	 * Verifies that promotion removes an unneeded hunk and keeps targeted runs outside the evidence counts.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void promotedAttemptKeepsOnlyNeededHunks() throws IOException
	{
		// Requirement: R-D11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = minimalityHarness(cleanup.add(Files.createTempDirectory("minimality")),
				new AtomicInteger());
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Overlay promoted = harness.overlays().require(campaign.development().working().snapshot());
				Write entry = (Write)
					promoted.entries().get(DriverHarness.PROMPT);
				requireThat(new String(entry.content().bytes(), UTF_8), "prompt").
					isEqualTo("needed\nseparator\nlast\n");
				requireThat(campaign.development().counts(), "counts").isEmpty();
				requireThat(campaign.attempts(), "allowance").isEmpty();
				requireThat(campaign.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
				requireThat(report.toJson(JsonMapper.builder().build()).path("removed_hunks").get(0).asString(),
					"reportedRemoval").isEqualTo("CLAUDE.md#2");
				requireThat(report.summary(ZoneOffset.UTC), "summary").contains("CLAUDE.md#2");
			}
		}
	}
	/**
	 * Verifies that an uncited author edit contributes no candidate and the next attempt runs.
	 *
	 * @throws IOException if the fixture cannot be read or written
	 */
	@Test
	public void uncitedHunkRetainedAndRetried() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new Builder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(new Failure(Builder.targeted("safety", "cache"), "failure", List.of(), null, List.of(), List.of())).
					build());
			harness.authors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				return new SessionResult.Completed(edited.transcript(), List.of(List.of("""
					{"needed_edits": [], "classifications": [
					  {"instance":"%s","classification":"guidance"}], "hunks": [], "conflicts": []}
					""".formatted(harness.author().briefs().getLast().developmentId(0)))), edited.effects(), edited.model(),
					edited.reasoning(), edited.usage(), edited.windows(),
					edited.promptSources(), edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().working().number() == 2 ||
					harness.author().briefs().size() == 2);
				requireThat(harness.campaign().candidates(), "candidates").isEqualTo(1);
				requireThat(harness.author().briefs().size(), "authors").isEqualTo(2);
				requireThat(harness.campaign().optimization().authors().size(), "retained").isEqualTo(1);
				requireThat(harness.campaign().optimization().authors().getFirst().rejections(), "rejections").
					contains("Uncited changed hunk: CLAUDE.md#1");
				requireThat(Files.isRegularFile(harness.campaign().optimization().authors().getFirst().effects().
					resolve(DriverHarness.PROMPT)), "effects").isTrue();
			}
		}
	}
	/**
	 * Verifies that a restart during reduction preserves the last passing snapshot and reuses working references.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void resumesMinimalityAfterRestart() throws IOException
	{
		// Requirement: R-D11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			AtomicInteger references = new AtomicInteger();
			DriverHarness harness = minimalityHarness(cleanup.add(Files.createTempDirectory("minimality")), references);
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().author() != null &&
					state.development().author().minimality().testing() != null);
			}
			Campaign saved = harness.campaign();
			requireThat(saved.development().working().number(), "working").isEqualTo(1);
			requireThat(saved.development().author().minimality().acceptedRuns(), "passingEvidence").isNotEmpty();
			try (Driver driver = harness.open())
			{
				Campaign resumed = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Write entry = (Write) harness.overlays().require(resumed.development().working().snapshot()).
					entries().get(DriverHarness.PROMPT);
				requireThat(new String(entry.content().bytes(), UTF_8), "prompt").isEqualTo("needed\nseparator\nlast\n");
				requireThat(resumed.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				requireThat(resumed.development().counts(), "counts").isEmpty();
				requireThat(resumed.attempts(), "allowance").isEmpty();
			}
			requireThat(references.get(), "workingReferences").isEqualTo(1);
		}
	}

	/**
	 * Verifies that an unknown citation is rejected even when every actual hunk has evidence.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void unknownCitationRetainedAndRetried() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new Builder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(Builder.targeted("safety", "cache")).build());
			harness.authors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				String response = """
					{"needed_edits":[],"classifications":[{"instance":"%s","classification":"guidance"}],
					 "hunks":[{"path":"CLAUDE.md","before_start":0,"before_end":1,"after_start":0,"after_end":1,
					 "instances":["unknown"],"analogous":"related case","dissimilar":"plain_list"}],"conflicts":[]}
					""".formatted(harness.author().briefs().getLast().developmentId(0));
				return new SessionResult.Completed(edited.transcript(), List.of(List.of(response)), edited.effects(),
					edited.model(),
					edited.reasoning(), edited.usage(), edited.windows(), edited.promptSources(),
					edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> harness.author().briefs().size() == 2);
				requireThat(harness.campaign().candidates(), "candidates").isEqualTo(1);
				requireThat(harness.campaign().optimization().authors().getFirst().rejections(), "rejections").
					isEqualTo(List.of("Unknown failing instance unknown cited at CLAUDE.md"));
			}
		}
	}

	/**
	 * Verifies that no-edit classifications survive the end of a round without a promotion.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void noEditRetainsRoundDiagnosis() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new Builder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(Builder.targeted("safety", "cache")).build());
			harness.authors(_ -> harness.completed("""
				{"needed_edits":[],"classifications":[{"instance":"%s","classification":"not_guidance"}],
				 "hunks":[],"conflicts":[]}
				""".formatted(harness.author().briefs().getLast().developmentId(0)), DriverHarness.MODEL));
			try (Driver driver = harness.open())
			{
				Campaign ended = harness.runUntil(driver, state -> state.development().round() == null);
				requireThat(ended.candidates(), "candidates").isEqualTo(1);
				requireThat(ended.optimization().authors().size(), "diagnoses").isEqualTo(2);
				requireThat(ended.optimization().authors().getFirst().classifications().values(), "classification").
					contains(FailureKind.NOT_GUIDANCE);
				StatusReport report = StatusReport.of(ended, ReportEvidence.NONE);
				JsonNode diagnoses = report.toJson(JsonMapper.builder().build()).path("author_diagnoses");
				requireThat(diagnoses.size(), "reportedDiagnoses").isEqualTo(2);
				requireThat(diagnoses.toString(), "diagnoses").contains("\"not_guidance\"");
				requireThat(report.summary(ZoneOffset.UTC), "summary").contains("not guidance");
			}
		}
	}

	/**
	 * Verifies that a campaign whose only round produced no promotable attempt keeps its working candidate and keeps
	 * admitting that candidate's representative trials while every cell has at most the failure limit.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void failedRoundResumesSampling() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("failed-round")),
				authoring().build());
			Candidate working = harness.campaign().development().working();
			harness.trials(request ->
			{
				if (harness.snapshot(request).equals(working.snapshot()))
					return harness.passed();
				return harness.failed();
			});
			try (Driver driver = harness.open())
			{
				Campaign ended = harness.runUntil(driver, state -> state.development().round() == null);
				requireThat(ended.outcome(), "outcome").isNull();
				requireThat(ended.pending(), "pending").isNull();
				requireThat(ended.development().working(), "working").isEqualTo(working);
				requireThat(ended.optimization().attempts().size(), "retainedAttempts").isEqualTo(2);
				requireThat(ended.optimization().attempts().stream().noneMatch(AttemptResult::accepted), "accepted").
					isTrue();
				int before = developmentOutcomes(ended);

				Campaign sampled = harness.runUntil(driver, state -> developmentOutcomes(state) > before + 1);
				requireThat(sampled.development().working(), "sampledWorking").isEqualTo(working);
				requireThat(sampled.outcome(), "sampledOutcome").isNull();
				requireThat(harness.author().briefs().size(), "authors").isEqualTo(2);
			}
		}
	}

	/**
	 * Creates an author and trial fixture whose second hunk adds unnecessary guidance.
	 *
	 * @param directory the owned fixture directory
	 * @param references counts runs of the working reference snapshot
	 * @return the fixture
	 * @throws IOException if it cannot be created
	 */
	private static DriverHarness minimalityHarness(Path directory, AtomicInteger references) throws IOException
	{
		DriverHarness harness = new DriverHarness(directory,
			new Builder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
				failure(Builder.targeted("safety", "cache")).
				regression(Builder.targeted("formatting", "formal"), null).build());
		Sha256 working = Builder.snapshot("candidate-1");
		harness.overlays().put(working, harness.candidate("first\nseparator\nlast\n"));
		harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
		harness.authors(request -> harness.edit(request, DriverHarness.PROMPT, "needed\nseparator\nunneeded\n"));
		harness.trials(request ->
		{
			try
			{
				if (harness.snapshot(request).equals(working) && harness.campaign().development().author() != null &&
					harness.campaign().development().author().minimality().testing() == null)
				{
					references.incrementAndGet();
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
			if (DriverHarness.cell(request).equals("formatting"))
				return harness.passed();
			try
			{
				Overlay overlay = harness.overlays().require(harness.snapshot(request));
				Write entry = (Write) overlay.entries().get(DriverHarness.PROMPT);
				if (entry != null && new String(entry.content().bytes(), UTF_8).lines().
					anyMatch(line -> line.equals("needed")))
				{
					return harness.passed();
				}
				return harness.failed();
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		});
		return harness;
	}

	/**
	 * Creates the test.
	 */
	public DriverTest()
	{
	}

	/**
	 * Returns the confirmation of the fixture contract.
	 *
	 * @return the event
	 */
	private static CampaignEvent.Confirm confirm()
	{
		return new CampaignEvent.Confirm(ContractFixtures.contract().withScope(CertificationScope.LATEST_VERSIONS),
			Builder.contractHash(), 11);
	}

	/**
	 * Verifies that no session starts before the user confirms the contract, and that trials start after.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void nothingRunsBeforeConfirmation() throws IOException
	{
		// Requirement: R-C2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().build());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
			}
			try (Driver driver = harness.open())
			{
				driver.apply(confirm());
				harness.runUntil(driver, _ -> !harness.sessions().sessions().isEmpty());
				requireThat(harness.sessions().sessions().getFirst().request().role(), "role").
					isEqualTo(SessionRole.TRIAL);
			}
		}
	}

	/**
	 * Confirmation, development to readiness, commit, an all-pass formal attempt, certification and
	 * delivery.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void certify() throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().working(1).build());
			try (Driver driver = harness.open())
			{
				driver.apply(confirm());
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
				requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
				requireThat(campaign.delivery().label(), "label").isEqualTo(Outcome.CERTIFIED);
				requireThat(harness.deliverer().calls().size(), "deliveries").isEqualTo(1);
				CertificationAttempt certified = campaign.attempt(campaign.delivery().attempt());
				CertificationRecord record = harness.deliverer().calls().getFirst().record().orElseThrow();
				requireThat(record.certificationIndex(), "certificationIndex").isEqualTo(certified.index());
				requireThat(record.identity(), "identity").isEqualTo(certified.identity());
				requireThat(result.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Delivered.class);
				requireThat(result.status().status(), "status").isEqualTo(new ReportStatus.Ended(Outcome.CERTIFIED));
			}
		}
	}

	/**
	 * Returns boundaries that certify a category at {@code 100 + 50·F} passes, find it clearly below at 5 failures,
	 * and never prove it better or worse than the incumbent.
	 *
	 * @return the boundaries
	 */
	private static DecisionBoundaries neutralBoundaries()
	{
		ComparisonBoundaries neutral = Boundaries.comparison(Boundaries.STANDARD_CAP, 1000, 0, Boundaries.STANDARD_CAP + 1);
		return Boundaries.decision(Boundaries.STANDARD_CAP, 100, 50, 5, List.of(neutral, neutral));
	}

	/**
	 * A development failure, a rerun that reproduces it, an author's edit, promotion runs, and a new working
	 * candidate whose counts start at zero with the concurrency window back to 1. The author receives the judge's
	 * rationales for the failure and for its rerun. The optimization worktree holds the new working candidate.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void editAfterFailure() throws IOException
	{
		// Requirement: R-D2
		// Requirement: R-D3
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyBaseline().development("formatting", 50, 0).
					development("safety", 50, 0).build());
			Sha256 first = Builder.snapshot("candidate-1");
			harness.trials(request ->
			{
				if (DriverHarness.cell(request).equals("safety") && harness.snapshot(request).equals(first))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Sha256 promoted = campaign.development().working().snapshot();
				requireThat(promoted, "promoted").isNotEqualTo(first);
				requireThat(campaign.development().counts(), "counts").isEmpty();
				requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(1);
				AuthorBrief brief = harness.author().briefs().getFirst();
				requireThat(brief.cell(), "category").isEqualTo("safety");
				// The author receives the judge's rationale for the failure and for the rerun that reproduced it
				Failure failure = brief.developmentFailures().getFirst();
				String rationale = "The trial did not meet the requirements.";
				requireThat(failure.rationale(), "rationale").isEqualTo(rationale);
				requireThat(failure.reruns(), "reruns").
					isEqualTo(List.of(new DiagnosticRerun(Verdict.FAIL, rationale)));
				// Only the development failure is triaged; its diagnostic rerun is not
				requireThat(failure.triage().impact(), "impact").isEqualTo(Impact.SELF_RECOVERING);
				requireThat(triageRequests(harness).size(), "triages").isEqualTo(1);
				// The author session's project source is the first authoring copy that the workspace prepared
				SessionRequest author = harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.AUTHOR).findFirst().orElseThrow();
				requireThat(author.source(), "source").
					isEqualTo(harness.directory().resolve("workspace").resolve("authoring").resolve("1"));
				requireThat(harness.workspace().materialized().getLast().identity(), "materialized").
					isEqualTo(promoted);
			}
		}
	}

	/**
	 * A development failure, three passing reruns, and a continuation whose concurrency window starts at 1
	 * and doubles after each window of passes.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void continueAfterFailure() throws IOException
	{
		// Requirement: R-D2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyBaseline().development("formatting", 50, 0).
					development("safety", 50, 0).build());
			AtomicBoolean failedOnce = new AtomicBoolean();
			harness.trials(request ->
			{
				if (DriverHarness.cell(request).equals("safety") && failedOnce.compareAndSet(false, true))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().diagnosis() != null);
				Campaign campaign = harness.runUntil(driver, state -> state.development().diagnosis() == null);
				requireThat(campaign.development().count("safety").failures(), "failures").isEqualTo(1);
				requireThat(campaign.development().working().number(), "working").isEqualTo(1);
				requireThat(campaign.slowStart(), "slowStart").isEqualTo(SlowStart.INITIAL);

				harness.clearOpenTrials();
				harness.runUntil(driver, state -> harness.openTrials().stream().filter(count -> count > 0).count() >= 3);
				List<Integer> windows = harness.openTrials().stream().filter(count -> count > 0).limit(3).toList();
				requireThat(windows, "windows").isEqualTo(List.of(1, 2, 4));
			}
		}
	}

	/**
	 * In a formal attempt, a later trial's failure completes before an earlier trial; the decision waits for
	 * the earlier trial, and an outcome adopted after the decision does not change it.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void outOfOrderCompletion() throws IOException
	{
		// Requirement: R-E5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			// The safety category has 4 outcomes left before its cap, so exactly 4 trials are admitted
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					slowStart(new SlowStart(4, 0)).certificationAttempt(1).boundaries(neutralBoundaries()).
					cell("formatting", 100, 0).cell("safety", 292, 4).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "trials").isEqualTo(4);

				trials.get(0).end(harness.passed());
				harness.settle(driver);
				trials.get(2).end(harness.failed());
				harness.settle(driver);
				CertificationAttempt attempt = harness.campaign().attempt(1);
				requireThat(attempt.outcome(), "outcome").isNull();
				requireThat(attempt.count("safety"), "safety").isEqualTo(Builder.counts(293, 4));

				trials.get(1).end(harness.passed());
				harness.settle(driver);
				attempt = harness.campaign().attempt(1);
				requireThat(attempt.outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
				requireThat(attempt.count("safety").outcomes(), "outcomes").isEqualTo(299);

				trials.get(3).end(harness.passed());
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
				requireThat(campaign.attempt(1).count("safety").outcomes(), "laterOutcomes").isEqualTo(299);
				requireThat(triageRequests(harness), "triages").isEmpty();
			}
		}
	}

	/**
	 * Returns a builder for a confirmed campaign that develops candidate 1 and has met its baseline.
	 *
	 * @return the builder
	 */
	private static Builder developing()
	{
		return new Builder().confirmed().working(1).readyBaseline().development("formatting", 50, 0).
			development("safety", 50, 0);
	}

	/**
	 * Returns the number of outcomes that a campaign's development adopted.
	 *
	 * @param campaign the campaign
	 * @return the outcomes of every category
	 */
	private static int developmentOutcomes(Campaign campaign)
	{
		int outcomes = 0;
		for (CellCounts counts : campaign.development().counts().values())
			outcomes += counts.outcomes();
		return outcomes;
	}

	/**
	 * Returns the persisted trials in a state.
	 *
	 * @param campaign the campaign
	 * @param state the state
	 * @return the trials, in assignment order
	 */
	private static List<LogicalTrial> trials(Campaign campaign, TrialState state)
	{
		return campaign.trials().stream().filter(trial -> trial.state() == state).toList();
	}

	/**
	 * The process crashes after an outcome is recorded but before the snapshot that adopts it is replaced.
	 * The next driver's reconciliation adopts the outcome exactly once.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void crashAfterOutcome() throws IOException
	{
		// Requirement: R-E1
		// Requirement: R-E11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.crashingStore().crashAfter(CrashingStore.Write.OUTCOME);
			try (Driver driver = harness.open())
			{
				try
				{
					harness.runSlice(driver);
					throw new AssertionError("The store did not crash");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").isEqualTo("The process crashed");
				}
			}
			Campaign crashed = harness.campaign();
			requireThat(developmentOutcomes(crashed), "outcomes").isEqualTo(100);
			requireThat(trials(crashed, TrialState.RUNNING).size(), "running").isEqualTo(1);

			harness.crashingStore().restart();
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(101);
				requireThat(reconciled.evidenceRevision(), "evidenceRevision").
					isEqualTo(crashed.evidenceRevision() + 1);
				requireThat(reconciled.trials(), "trials").isEmpty();
			}
			try (Driver _ = harness.open())
			{
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(101);
			}
		}
	}

	/**
	 * Verifies that no session starts before its assignment and the snapshot that starts it are persisted, and that
	 * a lost assignment is drawn again with the same identity.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void crashAfterAssignment() throws IOException
	{
		// Requirement: R-E1
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness control = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			try (Driver driver = control.open())
			{
				driver.step();
			}
			TrialKey expected = control.campaign().trials().getFirst().key();

			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.crashingStore().crashAfter(CrashingStore.Write.ASSIGNMENT);
			try (Driver driver = harness.open())
			{
				try
				{
					driver.step();
					throw new AssertionError("The store did not crash");
				}
				catch (IOException e)
				{
					requireThat(e.getMessage(), "message").isEqualTo("The process crashed");
				}
			}
			requireThat(harness.sessions().sessions(), "sessions").isEmpty();
			requireThat(harness.campaign().trials(), "trials").isEmpty();

			harness.crashingStore().restart();
			try (Driver driver = harness.open())
			{
				driver.step();
			}
			requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(1);
			requireThat(harness.campaign().trials().getFirst().key(), "key").isEqualTo(expected);
		}
	}

	/**
	 * A host interrupt mid-slice stops admissions, interrupts every session and persists the interrupted
	 * trials for a rerun.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void hostInterrupt() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(4, 0)).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> started = harness.sessions().sessions();
				requireThat(started.size(), "started").isEqualTo(4);

				harness.clock().advance(Duration.ofSeconds(10));
				harness.interrupts().interrupt(harness.clock().instant());
				Step step = driver.step();
				requireThat(step, "step").isInstanceOf(Step.Stopped.class);
				SliceResult result = ((Step.Stopped) step).result();
				requireThat(result.status().outcome(), "outcome").isEqualTo("interrupted");
				for (FakeSession session : started)
					requireThat(session.isInterruptRequested(), "isInterruptRequested").isTrue();
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(4);
				Campaign campaign = harness.campaign();
				requireThat(trials(campaign, TrialState.ASSIGNED).size(), "assigned").isEqualTo(4);
				requireThat(trials(campaign, TrialState.RUNNING), "running").isEmpty();
			}
		}
	}

	/**
	 * A certification attempt reaches its cap without a decision, ends inconclusive, and the campaign returns
	 * to development.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void inconclusive() throws IOException
	{
		// Requirement: R-E5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().certificationAttempt(1).boundaries(neutralBoundaries()).cell("formatting", 295, 4).
					cell("safety", 100, 0).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "trials").isEqualTo(1);
				requireThat(DriverHarness.cell(trials.getFirst().request()), "category").isEqualTo("formatting");
				trials.getFirst().end(harness.passed());
				harness.settle(driver);

				Campaign campaign = harness.campaign();
				requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.INCONCLUSIVE);
				requireThat(campaign.runningAttempt().isEmpty(), "running").isTrue();
				harness.runUntil(driver, state -> !state.trials().isEmpty());
				for (LogicalTrial trial : harness.campaign().trials())
					requireThat(trial.key().purpose(), "purpose").isEqualTo(Purpose.DEVELOPMENT);
			}
		}
	}

	/**
	 * A rate-limit rejection drops the window to 1 and holds admissions, including judges, until the
	 * provider's reset; then the same logical trial reruns. A rejection is not an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void rateLimit() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(2, 0)).build());
			Instant resetsAt = DriverHarness.START.plus(Duration.ofMinutes(2));
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> started = harness.runningTrials();
				requireThat(started.size(), "started").isEqualTo(2);
				SessionRequest limitedRequest = started.getFirst().request();
				started.getFirst().end(new SessionResult.RateLimited(resetsAt, List.of()));
				started.getLast().end(harness.passed());

				Step held = driver.step();
				Campaign campaign = harness.campaign();
				requireThat(ConcurrencyPolicy.window(campaign), "window").isEqualTo(1);
				requireThat(campaign.throttle().holdUntil(), "holdUntil").isEqualTo(resetsAt);
				List<LogicalTrial> queued = trials(campaign, TrialState.ASSIGNED);
				requireThat(queued.size(), "queued").isEqualTo(1);
				LogicalTrial limited = queued.getFirst();
				requireThat(limited.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(2);
				requireThat(held, "held").isEqualTo(new Step.Continue(resetsAt));

				harness.clock().set(resetsAt);
				harness.settle(driver);
				Campaign resumed = harness.campaign();
				requireThat(Reconciler.find(resumed, limited.key()).orElseThrow().state(), "state").
					isEqualTo(TrialState.RUNNING);
				FakeSession rerun = harness.runningTrials().getLast();
				requireThat(rerun.request(), "rerun").isEqualTo(limitedRequest);
			}
		}
	}

	/**
	 * A completed trial session appends a session record to its trial's record: when the session started and ended,
	 * how many trial sessions were running when it started, the throttle state after its reported rate-limit windows,
	 * those windows, the harness's login and the session's token usage.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	// Requirement: R-E6
	@Test
	public void completedTrialRecordsSession() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("trial-session")),
				developing().slowStart(new SlowStart(2, 0)).build(), HostLogin.SUBSCRIPTION);
			List<RateLimitWindow> windows = List.of(
				new RateLimitWindow(0.85, DriverHarness.START.plus(Duration.ofHours(5))),
				new RateLimitWindow(0.10, null));
			TokenUsage usage = new TokenUsage(500, 100, 60, 20);
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> started = harness.runningTrials();
				requireThat(started.size(), "started").isEqualTo(2);
				harness.clock().advance(Duration.ofMinutes(3));
				SessionResult.Completed passed = harness.passed();
				started.getLast().end(new SessionResult.Completed(passed.transcript(), passed.responses(),
					passed.effects(), passed.model(), passed.reasoning(), usage, windows, passed.promptSources(),
					passed.toolCalls()));
				driver.step();

				// The 85% window stops the concurrency window from growing beyond the 2 trials that are running
				Throttle frozen = new Throttle(Instant.EPOCH, 2, 0);
				requireThat(recordedSessions(harness), "sessions").isEqualTo(List.of(new TrialSession(
					DriverHarness.START, DriverHarness.START.plus(Duration.ofMinutes(3)), 2, frozen, windows,
					HostLogin.SUBSCRIPTION, usage)));
			}
		}
	}

	/**
	 * A rate-limited trial session appends a session record without token usage whose throttle state holds
	 * admissions until the reported reset.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	// Requirement: R-E6
	@Test
	public void rateLimitedTrialRecordsSession() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("limited-session")),
				developing().slowStart(new SlowStart(2, 0)).build(), HostLogin.API_KEY);
			Instant resetsAt = DriverHarness.START.plus(Duration.ofMinutes(30));
			List<RateLimitWindow> windows = List.of(new RateLimitWindow(1.0, resetsAt));
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> started = harness.runningTrials();
				requireThat(started.size(), "started").isEqualTo(2);
				harness.clock().advance(Duration.ofMinutes(1));
				started.getFirst().end(new SessionResult.RateLimited(resetsAt, windows));
				driver.step();

				requireThat(recordedSessions(harness), "sessions").isEqualTo(List.of(new TrialSession(
					DriverHarness.START, DriverHarness.START.plus(Duration.ofMinutes(1)), 1,
					new Throttle(resetsAt, 1, 0), windows, HostLogin.API_KEY, null)));
			}
		}
	}

	/**
	 * Returns the session records that the driver appended to the records of the campaign's trials.
	 *
	 * @param harness the test's ports
	 * @return the records, grouped by trial in the campaign's trial order
	 * @throws IOException if the store cannot be read
	 */
	private static List<TrialSession> recordedSessions(DriverHarness harness) throws IOException
	{
		List<TrialSession> sessions = new ArrayList<>();
		for (LogicalTrial trial : harness.campaign().trials())
			sessions.addAll(harness.crashingStore().trialSessions(trial.key()));
		return sessions;
	}

	/**
	 * Verifies that a rate-limit rejection of a judge session holds admissions until the provider's reset and then
	 * reruns the judge with the same evidence, without counting the rejection as a failure of the judge or of the
	 * trial.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void judgeRateLimit() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(1, 0)).build());
			AtomicInteger rejections = new AtomicInteger(Driver.JUDGE_ATTEMPTS);
			AtomicReference<Instant> resetsAt = new AtomicReference<>();
			harness.judges(request ->
			{
				if (rejections.getAndDecrement() > 0)
					return new SessionResult.RateLimited(resetsAt.get(), List.of());
				return harness.scriptedJudgment(request);
			});
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.endSessions(), "trials").isEqualTo(1);
				for (int rejection = 1; rejection <= Driver.JUDGE_ATTEMPTS; ++rejection)
				{
					Instant reset = harness.clock().instant().plus(Duration.ofMinutes(2));
					resetsAt.set(reset);
					harness.settle(driver);
					requireThat(judgeRequests(harness).size(), "judges").isEqualTo(rejection);
					requireThat(harness.campaign().throttle().holdUntil(), "holdUntil").isEqualTo(reset);
					harness.clock().set(reset);
				}
				harness.settle(driver);
				List<SessionRequest> judges = judgeRequests(harness);
				requireThat(judges.size(), "judges").isEqualTo(Driver.JUDGE_ATTEMPTS + 1);
				for (SessionRequest judge : judges)
					requireThat(judge, "judge").isEqualTo(judges.getFirst());
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(101);
			}
		}
	}

	/**
	 * An unowned change in the optimization worktree pauses the campaign before any session starts, until
	 * the user decides; resuming restores the working candidate.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void unownedChange() throws IOException
	{
		// Requirement: R-W7
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.workspace().changeWorktree(new FileInventory(Path.of("/project"), Map.of(DriverHarness.PROMPT,
				new FileEntry.RegularFile(Sha256.of("user edit".getBytes(UTF_8)),
					PosixFilePermissions.fromString("rw-r--r--")))));
			try (Driver driver = harness.open())
			{
				requireThat(harness.campaign().pending(), "pending").isInstanceOf(PendingDecision.UnownedChange.class);
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(harness.campaign().choices(), "choices").contains(Choice.RESUME);
			}
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.RESUME));
				requireThat(harness.campaign().pending(), "pending").isNull();
				requireThat(harness.workspace().materialized().getLast().snapshot(), "worktree").
					isEqualTo(harness.overlays().require(Builder.snapshot("candidate-1")).snapshot());
				driver.step();
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(1);
			}
		}
	}

	/**
	 * A contract revision requested mid-attempt lets the running trials drain, stops the attempt, and waits
	 * for confirmation; the stopped attempt never resumes.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void revisionDuringFormal() throws IOException
	{
		// Requirement: R-L3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).cell("formatting", 50, 0).cell("safety", 50, 0).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.runningTrials().size(), "trials").isEqualTo(1);
				driver.apply(new CampaignEvent.Revise());
				requireThat(harness.campaign().attempt(1).outcome(), "outcome").
					isEqualTo(Outcome.STOPPED_FOR_CONTRACT_REVISION);
				try
				{
					driver.apply(confirm());
					throw new AssertionError("A confirmation was accepted while a trial ran");
				}
				// Confirmation waits for the running trial to end
				catch (IllegalStateException _)
				{
				}
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.running(), "running").isEmpty();
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(result.status().pending(), "pending").isEqualTo(SliceReports.REVISION_PENDING);
			}
			int before = harness.sessions().sessions().size();
			try (Driver driver = harness.open())
			{
				driver.apply(confirm());
				harness.runUntil(driver, _ -> harness.sessions().sessions().size() >= before + 10);
				Campaign campaign = harness.campaign();
				requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.STOPPED_FOR_CONTRACT_REVISION);
				requireThat(campaign.runningAttempt().isEmpty(), "running").isTrue();
				for (LogicalTrial trial : campaign.trials())
					requireThat(trial.key().purpose(), "purpose").isNotEqualTo(Purpose.FORMAL);
			}
		}
	}

	/**
	 * When the driver cannot open a campaign and releasing its lock then fails too, the open failure is reported with
	 * the release failure attached, so the user sees the original cause.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	@SuppressWarnings("PMD.CloseResource")
	public void openKeepsFailureWhenUnlockFails() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver-unlock")),
				developing().build());
			IOException release = new IOException("release failed");
			try (FaultingFileChannel channel = new FaultingFileChannel(null, release, null))
			{
				// Driver.open takes ownership of the lock and releases it when it fails
				CampaignLock lock = CampaignLock.tryAcquire(channel).orElseThrow();
				try
				{
					Driver.open(harness.context(), "missing-campaign", lock).close();
					throw new AssertionError("Expected the missing campaign to fail");
				}
				catch (IllegalArgumentException e)
				{
					requireThat(List.of(e.getSuppressed()), "suppressed").isEqualTo(List.of(release));
				}
			}
		}
	}

	/**
	 * While one driver holds a campaign, a second one changes nothing and reports the campaign busy.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void busy() throws IOException, InterruptedException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			try (Driver _ = harness.open())
			{
				requireThat(Driver.open(harness.context(), DriverHarness.CAMPAIGN).isEmpty(), "isEmpty").isTrue();
				Campaign before = harness.campaign();
				SliceResult result = SliceRunner.run(harness.context(), DriverHarness.CAMPAIGN,
					List.of(new CampaignEvent.Cancel()));
				requireThat(result.status().status(), "status").isEqualTo(new ReportStatus.Active(Status.BUSY));
				requireThat(harness.campaign(), "campaign").isEqualTo(before);
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
			}
		}
	}

	/**
	 * A certification attempt reaches its cap with one category proven better and the other certified.
	 * The campaign pauses with {@code keep improvement}, {@code optimize further} and {@code stop}; the choice is
	 * still pending after a restart, and keeping the improvement delivers the attempt's candidate labelled
	 * {@code improved, not certified}.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void keepImprovement() throws IOException
	{
		// Requirement: R-F6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).cell("formatting", 295, 4).cell("safety", 100, 0).build());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.ImprovementProven.class);
				requireThat(campaign.choices(), "choices").
					isEqualTo(List.of(Choice.KEEP_IMPROVEMENT, Choice.OPTIMIZE_FURTHER, Choice.STOP));
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
			}
			try (Driver driver = harness.open())
			{
				requireThat(harness.campaign().pending(), "pending").
					isInstanceOf(PendingDecision.ImprovementProven.class);
				driver.apply(CampaignEvent.Choose.keep(1));
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.delivery(), "delivery").isEqualTo(new DeliveryRequest(
					new Candidate(1, Builder.snapshot("candidate-1")), 1, Outcome.IMPROVED_NOT_CERTIFIED));
				requireThat(harness.deliverer().calls().size(), "deliveries").isEqualTo(1);
				requireThat(harness.deliverer().calls().getFirst().selected(), "selected").
					isEqualTo(harness.overlays().require(Builder.snapshot("candidate-1")));
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Ended(Outcome.IMPROVED_NOT_CERTIFIED));
			}
		}
	}

	/**
	 * An attempt qualifies for keep improvement, the user optimizes further, a later candidate's attempt
	 * also qualifies with a lower bound on its weakest category, and later attempts fail. At the stop, the campaign
	 * offers the first candidate, and keeping it delivers that candidate rather than the working one.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void bestCandidateSoFar() throws IOException
	{
		// Requirement: R-F6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Candidate first = new Candidate(1, Builder.snapshot("candidate-1"));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(2).readyDevelopment().readyBaseline().
					certificationAttempt(1).candidate(first).cell("formatting", 296, 4).cell("safety", 250, 0).
					outcome(Outcome.INCONCLUSIVE).campaign().
					certificationAttempt(2).cell("formatting", 295, 4).cell("safety", 100, 0).build());
			harness.trials(_ ->
			{
				try
				{
					if (harness.campaign().runningAttempt().map(CertificationAttempt::index).orElse(0) >= 3)
						return harness.failed();
					return harness.passed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.pending() instanceof PendingDecision.ImprovementProven);
				requireThat(harness.campaign().attempt(2).outcome(), "outcome").isNotNull();
			}
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.OPTIMIZE_FURTHER));
				harness.runUntil(driver, state -> state.attempts().size() >= 3 && !state.attempt(3).isRunning());
				requireThat(harness.campaign().attempt(3).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
			}
			try (Driver driver = harness.open())
			{
				driver.apply(new CampaignEvent.Cancel());
				Campaign campaign = harness.campaign();
				requireThat(campaign.choices(), "choices").contains(Choice.KEEP_IMPROVEMENT);
				StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
				requireThat(report.keepOffers().getFirst().attempt(), "offer").isEqualTo(1);

				driver.apply(CampaignEvent.Choose.keep(1));
				harness.runSlice(driver);
				RecordingDeliverer.Call call = harness.deliverer().calls().getFirst();
				requireThat(call.request().candidate(), "candidate").isEqualTo(first);
				requireThat(call.selected(), "selected").isEqualTo(harness.overlays().require(first.snapshot()));
				requireThat(call.working(), "working").
					isEqualTo(harness.overlays().require(Builder.snapshot("candidate-2")));
				requireThat(call.record(), "record").isEmpty();
			}
		}
	}

	/**
	 * Mid-attempt, a run reports a different model. Its record holds no outcome and the campaign pauses.
	 * Restoring reruns the same instance and the attempt continues. A second change followed by a new measurement
	 * identity ends the attempt, returns to development and starts a new incumbent baseline; the qualifying attempt
	 * from before the change is offered as historical.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void measurementChangeDuringFormal() throws IOException
	{
		// Requirement: R-M5
		// Requirement: R-M3
		// Requirement: R-F6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Candidate first = new Candidate(1, Builder.snapshot("candidate-1"));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(2).readyDevelopment().readyBaseline().
					certificationAttempt(1).candidate(first).cell("formatting", 296, 4).cell("safety", 250, 0).
					outcome(Outcome.INCONCLUSIVE).campaign().
					certificationAttempt(2).cell("formatting", 50, 0).cell("safety", 50, 0).build());
			AtomicReference<String> model = new AtomicReference<>("model-b");
			harness.trials(_ -> harness.completed("pass", model.get()));
			Campaign changed;
			LogicalTrial observed;
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.runningTrials().size(), "trials").isEqualTo(1);
				harness.endSessions();
				harness.settle(driver);
				changed = harness.campaign();
				requireThat(changed.pending(), "pending").isInstanceOf(PendingDecision.MeasurementChanged.class);
				requireThat(changed.choices(), "choices").
					isEqualTo(List.of(Choice.RESTORE, Choice.NEW_IDENTITY, Choice.STOP));
				requireThat(harness.store().listUnadoptedOutcomes(changed), "outcomes").isEmpty();
				observed = changed.trials().getFirst();
				requireThat(observed.state(), "state").isEqualTo(TrialState.ASSIGNED);
				requireThat(observed.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
				requireThat(observed.observations(), "observations").isPositive();
				requireThat(changed.attempt(2).count("formatting").outcomes() +
					changed.attempt(2).count("safety").outcomes(), "outcomes").isEqualTo(100);
			}
			model.set(DriverHarness.MODEL);
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.RESTORE));
				driver.step();
				requireThat(harness.campaign().trials().getFirst().key(), "key").isEqualTo(observed.key());
				harness.endSessions();
				harness.settle(driver);
				Campaign restored = harness.campaign();
				requireThat(restored.attempt(2).isRunning(), "isRunning").isTrue();
				requireThat(restored.attempt(2).count("formatting").outcomes() +
					restored.attempt(2).count("safety").outcomes(), "outcomes").isEqualTo(101);

				model.set("model-b");
				harness.runUntil(driver, state -> state.pending() instanceof PendingDecision.MeasurementChanged);
			}
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.NEW_IDENTITY));
				Campaign renewed = harness.campaign();
				requireThat(renewed.attempt(2).outcome(), "outcome").isEqualTo(Outcome.TESTING_ENVIRONMENT_CHANGED);
				requireThat(renewed.runningAttempt().isEmpty(), "running").isTrue();
				requireThat(renewed.development().counts(), "counts").isEmpty();
				requireThat(renewed.baseline().index(), "baseline").isEqualTo(2);
				StatusReport report = StatusReport.of(renewed, ReportEvidence.NONE);
				requireThat(report.keepOffers().size(), "offers").isEqualTo(1);
				requireThat(report.keepOffers().getFirst().attempt(), "attempt").isEqualTo(1);
				requireThat(report.keepOffers().getFirst().historical(), "historical").isTrue();
			}
		}
	}

	/**
	 * A category whose file is unbound runs under two profiles. Each cell has its own counts, readiness,
	 * commit and decision; a trial runs under its cell's profile while the judge runs under the role profile, and
	 * each cell records the model its trials observed.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void cellsRunUnderTheirProfiles() throws IOException
	{
		// Requirement: R-C11
		// Requirement: R-E17
		Contract contract = ContractFixtures.twoCellContract();
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.OBSERVED.values());
		values.remove(MeasurementCondition.MODEL);
		Campaign start = new Builder().identity(new MeasurementIdentity(values)).confirmed(contract, 1).
			working(1).development(ContractFixtures.BIG_CELL, 100, 0).development(ContractFixtures.SMALL_CELL, 90, 0).
			development("safety", 100, 0).readyBaseline().build();
		requireThat(ReadinessGate.isCommitDue(start), "commitDueBeforeSmallCell").isFalse();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start);
			harness.trials(request ->
			{
				try
				{
					boolean formal = harness.campaign().runningAttempt().isPresent();
					// The weaker cell certifies first, which opens the stronger cell, whose trials fail
					if (formal && DriverHarness.cell(request).equals("formatting") &&
						request.profile().equals(ContractFixtures.BIG))
					{
						return harness.failed(request.profile().model());
					}
					return harness.completed("pass", request.profile().model());
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				Campaign committed = harness.runUntil(driver, state -> state.attempts().size() == 1);
				requireThat(committed.development().count(ContractFixtures.SMALL_CELL).passes(), "smallDevelopment").
					isGreaterThanOrEqualTo(100);
				requireThat(committed.development().count(ContractFixtures.BIG_CELL).passes(), "bigDevelopment").
					isEqualTo(100);
				campaign = harness.runUntil(driver, state -> !state.attempt(1).isRunning());
			}

			CertificationAttempt attempt = campaign.attempt(1);
			requireThat(attempt.procedure().cells(), "committedCells").
				isEqualTo(List.of(ContractFixtures.BIG_CELL, ContractFixtures.SMALL_CELL, "safety"));
			requireThat(attempt.count(ContractFixtures.SMALL_CELL).failures(), "smallFailures").isEqualTo(0);
			requireThat(attempt.count(ContractFixtures.BIG_CELL).passes(), "bigPasses").isEqualTo(0);
			DecisionBoundaries boundaries = attempt.procedure().boundaries();
			requireThat(DecisionTable.cellDecision(boundaries, attempt.count(ContractFixtures.BIG_CELL)),
				"bigDecision").isEqualTo(CellDecision.CLEARLY_BELOW);
			requireThat(DecisionTable.cellDecision(boundaries, attempt.count(ContractFixtures.SMALL_CELL)),
				"smallDecision").isEqualTo(CellDecision.CERTIFIED);
			requireThat(attempt.outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);

			Map<TrialProfile, Integer> formatting = new HashMap<>();
			int judges = 0;
			for (FakeSession session : harness.sessions().sessions())
			{
				SessionRequest request = session.request();
				if (request.role() == SessionRole.JUDGE)
				{
					++judges;
					requireThat(request.profile(), "judgeProfile").isEqualTo(contract.judge().profile());
				}
				else if (request.role() == SessionRole.TRIAL)
				{
					if (DriverHarness.cell(request).equals("safety"))
						requireThat(request.profile(), "safetyProfile").isEqualTo(ContractFixtures.BIG);
					else
						formatting.merge(request.profile(), 1, Integer::sum);
				}
			}
			requireThat(judges, "judges").isPositive();
			requireThat(formatting.keySet(), "formattingProfiles").
				isEqualTo(Set.of(ContractFixtures.BIG, ContractFixtures.SMALL));

			MeasurementIdentity identity = campaign.identity();
			requireThat(identity.cells().get(ContractFixtures.BIG_CELL).get(MeasurementCondition.MODEL), "bigModel").
				isEqualTo("big");
			requireThat(identity.cells().get(ContractFixtures.SMALL_CELL).get(MeasurementCondition.MODEL),
				"smallModel").isEqualTo("small");
		}
	}

	/**
	 * Under the latest-versions scope, development samples only each category's weakest cell, and the candidate is
	 * committed once that cell is ready. A formal failure in another scope cell makes that cell a development cell
	 * once reproduction trials reproduce it there, so the next commit waits until it is ready too.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void developmentCellsGrowOnFormalFailure() throws IOException
	{
		// Requirement: R-E19
		// Requirement: R-E3
		Contract contract = TierFixtures.tieredContract();
		Campaign start = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(TierFixtures.SMALL_HIGH, 90, 0).development(TierFixtures.SAFETY, 90, 0).build();
		requireThat(CellTier.scopeCellIds(start.contract()).size(), "scopeCells").isEqualTo(6);
		TrialProfile medium = new TrialProfile("big", "medium");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start);
			harness.trials(request ->
			{
				try
				{
					boolean firstAttempt = harness.campaign().runningAttempt().
						map(attempt -> attempt.index() == 1).orElse(false);
					boolean reproduction = runningTrial(harness, request).key().purpose() == Purpose.REPRODUCTION;
					if ((firstAttempt || reproduction) && request.profile().equals(medium))
						return harness.failed();
					return harness.passed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
			try (Driver driver = harness.open())
			{
				Campaign committed = harness.runUntil(driver, state -> state.attempts().size() == 1);
				requireThat(committed.development().counts().keySet(), "firstDevelopmentCells").
					isEqualTo(Set.of(TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
				requireThat(ReadinessGate.isReady(committed.development().count(TierFixtures.SMALL_HIGH)),
					"weakestReady").isTrue();

				Campaign failed = harness.runUntil(driver, state -> !state.attempt(1).isRunning());
				requireThat(failed.attempt(1).count(TierFixtures.BIG_MEDIUM).failures(), "formalFailures").
					isPositive();
				requireThat(CellTier.developmentCellIds(failed), "developmentCells").
					isEqualTo(List.of(TierFixtures.BIG_MEDIUM, TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));

				Campaign recommitted = harness.runUntil(driver, state -> state.attempts().size() == 2);
				Development development = recommitted.development();
				requireThat(ReadinessGate.isReady(development.count(TierFixtures.BIG_MEDIUM)), "failedCellReady").
					isTrue();
				for (String cell : List.of(TierFixtures.BIG_LOW, TierFixtures.BIG_HIGH, TierFixtures.BIG_XHIGH))
					requireThat(development.count(cell).outcomes(), cell).isEqualTo(0);
			}
		}
	}

	/**
	 * In Codex, a reproduction round in the lowest effort of its model that does not reproduce its group repeats
	 * under a tool-output token limit: its trial sessions start with the limit, a reproduction there makes the
	 * limited cell a development cell whose development trials start with the limit too, and the observed identity
	 * records the limit for that cell.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void codexReproducesUnderAToolOutputLimit() throws IOException
	{
		// Requirement: R-C19
		Contract contract = TierFixtures.tieredContract();
		String limited = TierFixtures.BIG_LOW + "/tool-output-5000";
		TrialInstance failed = new Generator(contract).draw(TierFixtures.BIG_LOW,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		TestDefinition test = contract.findTest(failed.testId()).orElseThrow();
		List<TriedCombination> tried = new ArrayList<>();
		for (int i = 1; i <= 4; ++i)
		{
			String suffix = "a_" + i;
			List<VariationValue> values = test.keyRules().stream().map(rule -> new VariationValue(
				rule.dimension() + "_" + suffix, rule.dimension() + " " + suffix, 0.25)).toList();
			int trials = 25;
			if (i == 4)
				trials = 24;
			tried.add(new TriedCombination(new AddedCombination(1, test.id(), values), trials, 0));
		}
		FailedRequirement wrongTone = new FailedRequirement("r1", "wrong_tone");
		Failure failure = new Failure(failed, "", List.of(wrongTone), null, List.of(), List.of());
		FailureGroup group = new FailureGroup(TierFixtures.BIG_LOW, test.id(), wrongTone, 2, FailureGroup.ROUNDS,
			List.of(new ReproductionRound(TierFixtures.BIG_LOW, tried)), FailureGroup.Status.REPRODUCING);
		Campaign start = new Builder().header(new Header(DriverHarness.CAMPAIGN, Path.of("/project"),
			Path.of("/project").resolve(DriverHarness.PROMPT), Harness.CODEX, Lifecycle.ACTIVE)).
			confirmed(contract, 1).working(1).readyBaseline().readyDevelopment().certificationAttempt(1).
			failure(failure).failure(failure).outcome(Outcome.BELOW_MINIMUM).groups(List.of(group)).campaign().build();
		List<TrialProfile> limitedProfiles = new CopyOnWriteArrayList<>();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start);
			harness.trials(request ->
			{
				try
				{
					if (request.profile().toolOutputTokenLimit() == 0)
						return harness.passed();
					limitedProfiles.add(request.profile());
					if (harness.campaign().reproducing().isPresent())
						return harness.failed();
					return harness.passed();
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
			});
			try (Driver driver = harness.open())
			{
				Campaign developed = harness.runUntil(driver, state ->
					state.development().count(limited).outcomes() > 0);

				FailureGroup reproduced = developed.attempt(1).group(1);
				requireThat(reproduced.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
				requireThat(reproduced.round().cellId(), "round().cellId()").isEqualTo(limited);
				requireThat(CellTier.developmentCellIds(developed), "developmentCells").contains(limited);
				requireThat(Set.copyOf(limitedProfiles), "limitedProfiles").
					isEqualTo(Set.of(new TrialProfile("big", "low", 5000)));
				requireThat(developed.identity().cells().getOrDefault(limited, Map.of()).
					get(MeasurementCondition.TOOL_OUTPUT_LIMIT), "toolOutputLimit").isEqualTo("5000");
			}
		}
	}

	/**
	 * A certification attempt whose formal trials fail in the weakest cell of a category ends there, and no formal
	 * trial runs in the category's stronger cells, which never open.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void weakestFailureOpensNoStrongerCell() throws IOException
	{
		// Requirement: R-E23
		Contract contract = TierFixtures.tieredContract();
		TrialProfile weakest = new TrialProfile("small", "high");
		Set<String> formalCells = ConcurrentHashMap.newKeySet();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().
					development(TierFixtures.SMALL_HIGH, 200, 0).development(TierFixtures.SAFETY, 200, 0).
					certificationAttempt(1).build());
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				if (trial.key().purpose() == Purpose.FORMAL)
					formalCells.add(trial.key().cell());
				if (request.profile().equals(weakest))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				Campaign ended = harness.runUntil(driver, state -> !state.attempt(1).isRunning());
				requireThat(ended.attempt(1).outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);
				requireThat(formalCells, "formalCells").isEqualTo(Set.of(TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
			}
		}
	}

	/**
	 * Development, diagnostic, promotion and reproduction trials run development instances, or values that development
	 * added, and request sessions of the development part; baseline and formal trials run held-out ones and request
	 * sessions of the held-out part. After a failed attempt, the author brief names each formal failure's cell, test
	 * and failed requirements, and the author's prompt holds no held-out instance, reply or judge rationale.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void heldOutInstancesStayOutOfDevelopment() throws IOException
	{
		// Requirement: R-C16
		// Requirement: R-D9
		// Requirement: R-C4
		// Requirement: R-D7
		// Requirement: R-E24
		Contract contract = TierFixtures.tieredContract();
		TrialProfile medium = new TrialProfile("big", "medium");
		String heldOutReply = "The held-out reply.";
		String heldOutRationale = "The held-out rationale.";
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).
					development(TierFixtures.SMALL_HIGH, 90, 0).development(TierFixtures.SAFETY, 90, 0).build());
			Map<Purpose, List<TrialInstance>> instancesByPurpose = new EnumMap<>(Purpose.class);
			Map<Purpose, Set<Part>> requestPartsByPurpose = new EnumMap<>(Purpose.class);
			AtomicBoolean developmentFailed = new AtomicBoolean();
			Campaign campaign;
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				Purpose purpose = trial.key().purpose();
				instancesByPurpose.computeIfAbsent(purpose, _ -> new ArrayList<>()).add(trial.instance());
				requestPartsByPurpose.computeIfAbsent(purpose, _ -> EnumSet.noneOf(Part.class)).add(request.part());
				if (request.profile().equals(medium))
				{
					if (purpose == Purpose.FORMAL)
						return harness.completed(heldOutReply, DriverHarness.MODEL);
					if (purpose == Purpose.DEVELOPMENT && developmentFailed.compareAndSet(false, true) ||
						purpose == Purpose.DIAGNOSTIC || purpose == Purpose.REPRODUCTION)
					{
						return harness.failed();
					}
				}
				return harness.passed();
			});
			harness.judges(request ->
			{
				if (request.turns().getFirst().contains(heldOutReply))
					return harness.judged(request, Verdict.FAIL, heldOutRationale);
				return harness.scriptedJudgment(request);
			});
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, state -> state.development().working().number() == 2);
			}
			requireThat(instancesByPurpose.keySet(), "purposes").isEqualTo(Set.of(Purpose.values()));
			for (Map.Entry<Purpose, List<TrialInstance>> entry : instancesByPurpose.entrySet())
			{
				Part expected = switch (entry.getKey())
				{
					case DEVELOPMENT, DIAGNOSTIC, PROMOTION, REPRODUCTION -> Part.DEVELOPMENT;
					case BASELINE, FORMAL -> Part.HELD_OUT;
				};
				for (TrialInstance instance : entry.getValue())
					requireThat(partOf(campaign, instance), entry.getKey().name()).isEqualTo(expected);
				requireThat(requestPartsByPurpose.get(entry.getKey()), entry.getKey().name() + ".requestParts").
					isEqualTo(Set.of(expected));
			}

			AuthorBrief brief = harness.author().briefs().getFirst();
			requireThat(brief.cell(), "cell").isEqualTo(TierFixtures.BIG_MEDIUM);
			List<SessionRequest> authorRequests = harness.sessions().sessions().stream().map(FakeSession::request).
				filter(request -> request.role() == SessionRole.AUTHOR).toList();
			String turn = authorRequests.getFirst().turns().getFirst();
			requireThat(turn, "turn").doesNotContain(heldOutReply).doesNotContain(heldOutRationale);
			for (TrialInstance formal : instancesByPurpose.get(Purpose.FORMAL))
			{
				for (String formalTurn : formal.turns())
					requireThat(turn, "turn").doesNotContain(formalTurn);
			}
			String endedSection = turn.substring(turn.indexOf("certification attempts that have ended"),
				turn.indexOf("\n\n### Your approach"));
			JsonNode ended = JsonMapper.builder().build().readTree(endedSection.substring(endedSection.indexOf('[')));
			requireThat(ended.size(), "ended.size()").isEqualTo(1);
			JsonNode failures = ended.get(0).get("failures");
			requireThat(failures.size(), "failures.size()").isPositive();
			for (JsonNode failure : failures)
			{
				requireThat(new HashSet<>(failure.propertyNames()), "fields").
					isEqualTo(Set.of("cell", "test", "failed_requirements"));
				requireThat(failure.get("cell").asString(), "cell").isEqualTo(TierFixtures.BIG_MEDIUM);
				// Requirement: R-C18
				String firstSemantic = contract.findTest(failure.get("test").asString()).orElseThrow().
					semanticRequirementIds().getFirst();
				Requirement judged = contract.requirements().stream().
					filter(requirement -> requirement.id().equals(firstSemantic)).findFirst().orElseThrow();
				JsonNode expected = JsonMapper.builder().build().createArrayNode().addObject().
					put("requirement_id", judged.id()).put("behavior", judged.behaviors().getFirst());
				requireThat(failure.get("failed_requirements"), "failedRequirements").
					isEqualTo(JsonMapper.builder().build().createArrayNode().add(expected));
			}
		}
	}

	/**
	 * When a certification attempt ends with formal failures, a deriver session writes values for each failure group
	 * without seeing the salt or a held-out value, and reproduction trials run them before any development trial.
	 * Once each group reproduces, its values join development, development counts restart and draw the added values,
	 * and the unchanged candidate is committed again only after every development cell is ready on new trials.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failedAttemptReproducesBeforeDevelopment() throws IOException
	{
		// Requirement: R-C19
		// Requirement: R-C17
		// Requirement: R-C16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().build());
			Map<String, Integer> newTrials = new HashMap<>();
			List<TrialInstance> newInstances = new ArrayList<>();
			AtomicBoolean developedBeforeReproduction = new AtomicBoolean();
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				Purpose purpose = trial.key().purpose();
				if (purpose == Purpose.FORMAL || purpose == Purpose.REPRODUCTION)
					return harness.failed();
				if (purpose == Purpose.DEVELOPMENT && isFirstAttemptEnded(harness))
				{
					if (isReproducing(harness))
						developedBeforeReproduction.set(true);
					newTrials.merge(trial.key().cell(), 1, Integer::sum);
					newInstances.add(trial.instance());
				}
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				Campaign ended = harness.runUntil(driver, state -> !state.attempts().isEmpty() &&
					!state.attempt(1).isRunning());
				List<FailureGroup> groups = ended.attempt(1).groups();
				requireThat(groups, "groups").isNotEmpty();
				Campaign recommitted = harness.runUntil(driver, state -> state.attempts().size() == 2);
				requireThat(developedBeforeReproduction.get(), "developedBeforeReproduction").isFalse();

				List<SessionRequest> deriverRequests = harness.sessions().sessions().stream().
					map(FakeSession::request).filter(request -> request.role() == SessionRole.DERIVER).toList();
				requireThat(deriverRequests.size(), "deriverRequests").isEqualTo(groups.size());
				Contract contract = recommitted.contract();
				for (SessionRequest request : deriverRequests)
				{
					String turn = request.turns().getFirst();
					requireThat(turn, "turn").doesNotContain("\"salt\"").
						doesNotContain(String.valueOf(contract.salt()));
					for (String label : heldOutOnlyLabels(contract))
						requireThat(turn, "turn").doesNotContain("\"" + label + "\"");
				}

				AddedValues added = recommitted.lineage().added();
				List<AddedCombination> reproduced = new ArrayList<>();
				for (FailureGroup group : recommitted.attempt(1).groups())
				{
					requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
					requireThat(group.round().failures(), "failures").isEqualTo(FailureGroup.FAILURES);
					requireThat(group.round().added().size(), "added").isGreaterThanOrEqualTo(4);
					reproduced.addAll(group.round().added());
				}
				requireThat(added.combinations(), "added").isEqualTo(reproduced);
				for (AddedCombination combination : added.combinations())
					requireThat(combination.attempt(), "attempt").isEqualTo(1);

				requireThat(recommitted.development().series(), "series").
					isGreaterThan(ended.development().series());
				for (String cell : CellTier.developmentCellIds(recommitted))
				{
					requireThat(newTrials.getOrDefault(cell, 0), cell).
						isGreaterThanOrEqualTo(ReadinessGate.MINIMUM_TRIALS);
					requireThat(recommitted.development().count(cell).outcomes(), cell).
						isGreaterThanOrEqualTo(ReadinessGate.MINIMUM_TRIALS);
				}
				Set<String> addedLabels = new HashSet<>();
				for (AddedCombination combination : added.combinations())
				{
					for (VariationValue value : combination.values())
						addedLabels.add(value.label());
				}
				requireThat(newInstances.stream().anyMatch(instance -> instance.choices().stream().
					anyMatch(choice -> addedLabels.contains(choice.label()))), "drewAddedValues").isTrue();
			}
		}
	}

	/**
	 * Returns a campaign whose first certification attempt failed with one formal failure of the
	 * {@code delete_directory} test in the {@code safety} cell, and whose failure group has given rounds.
	 *
	 * @param contract the confirmed contract
	 * @param cell the cell of the formal failure
	 * @param failed the requirement that the formal failure failed, with its behavior
	 * @param rounds the group's rounds
	 * @return the campaign
	 */
	private static Campaign reproducing(Contract contract, String cell, FailedRequirement failed,
		List<ReproductionRound> rounds)
	{
		TrialInstance instance = new Generator(contract).draw(cell, DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		Failure failure = new Failure(instance, "", List.of(failed), null, List.of(), List.of());
		return new Builder().confirmed(contract, 1).working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).failure(failure).outcome(Outcome.BELOW_MINIMUM).groups(List.of(
				new FailureGroup(cell, instance.testId(), failed, 1, FailureGroup.ROUNDS, rounds,
					FailureGroup.Status.REPRODUCING))).build();
	}

	/**
	 * Returns new combinations of a test, each with a count of trials and no failure.
	 *
	 * @param contract the confirmed contract
	 * @param testId the test's identifier
	 * @param prefix distinguishes the combinations of different rounds
	 * @param trials the trials of each combination
	 * @return the combinations
	 */
	private static List<TriedCombination> tried(Contract contract, String testId, String prefix, int... trials)
	{
		TestDefinition test = contract.findTest(testId).orElseThrow();
		List<TriedCombination> tried = new ArrayList<>();
		for (int i = 0; i < trials.length; ++i)
		{
			String suffix = prefix + "_" + (i + 1);
			List<VariationValue> values = test.keyRules().stream().map(rule -> new VariationValue(
				rule.dimension() + "_" + suffix, rule.dimension() + " " + suffix, 1.0 / trials.length)).toList();
			tried.add(new TriedCombination(new AddedCombination(1, testId, values), trials[i], 0));
		}
		return tried;
	}

	/**
	 * Indicates whether a trial runs a combination of a round.
	 *
	 * @param trial the trial
	 * @param round the round
	 * @param contract the confirmed contract
	 * @return {@code true} if the trial's instance holds one of the round's combinations
	 */
	private static boolean runs(LogicalTrial trial, ReproductionRound round, Contract contract)
	{
		TrialInstance instance = trial.instance();
		return round.indexOf(contract.findTest(instance.testId()).orElseThrow(), instance) >= 0;
	}

	/**
	 * When a round ends without reproducing its group and no stress setting exists, the deriver receives the tried
	 * values with their trial and failure counts, never a trial's reply, and its new values replace them. Only the
	 * values that reproduce the group join development.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void unreproducedValuesAreReplaced() throws IOException
	{
		// Requirement: R-C19
		Contract contract = ContractFixtures.contract();
		FailedRequirement failed = new FailedRequirement("r2", "deleted_without_asking");
		List<TriedCombination> first = tried(contract, "delete_directory", "first", 24, 24, 24, 24);
		Campaign campaign = reproducing(contract, "safety", failed,
			List.of(new ReproductionRound("safety", first)));
		ReproductionRound firstRound = campaign.attempt(1).group(1).round();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), campaign);
			String reply = "The reproduction reply.";
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				if (trial.key().purpose() == Purpose.REPRODUCTION)
				{
					if (runs(trial, firstRound, contract))
						return harness.completed(reply, DriverHarness.MODEL);
					return harness.failed();
				}
				return harness.passed();
			});
			harness.judges(request ->
			{
				if (request.turns().getFirst().contains(reply))
					return harness.judged(request, Verdict.PASS, "The trial met the requirements.");
				return harness.scriptedJudgment(request);
			});
			try (Driver driver = harness.open())
			{
				Campaign reproduced = harness.runUntil(driver, state -> state.reproducing().isEmpty());

				FailureGroup group = reproduced.attempt(1).group(1);
				requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
				requireThat(group.rounds().size(), "rounds").isEqualTo(2);
				requireThat(group.rounds().getFirst().trials(), "first.trials()").isEqualTo(FailureGroup.TRIALS);
				requireThat(group.round().cellId(), "cellId").isEqualTo("safety");
				requireThat(reproduced.lineage().added().combinations(), "added").isEqualTo(group.round().added());
				for (TriedCombination combination : first)
				{
					requireThat(reproduced.lineage().added().combinations(), "added").
						doesNotContain(combination.combination());
				}

				List<SessionRequest> deriverRequests = harness.sessions().sessions().stream().
					map(FakeSession::request).filter(request -> request.role() == SessionRole.DERIVER).toList();
				requireThat(deriverRequests.size(), "deriverRequests").isEqualTo(1);
				SessionRequest request = deriverRequests.getFirst();
				requireThat(request.turns().getFirst(), "turn").doesNotContain(reply);
				JsonNode rounds = harness.failureGroup(request).get("tried_rounds");
				requireThat(rounds.size(), "rounds.size()").isEqualTo(1);
				requireThat(rounds.get(0).get("cell").asString(), "cell").isEqualTo("safety");
				int trials = 0;
				for (JsonNode combination : rounds.get(0).get("combinations"))
				{
					requireThat(new HashSet<>(combination.propertyNames()), "fields").
						isEqualTo(Set.of("values", "trials", "failures"));
					requireThat(combination.get("failures").asInt(), "failures").isEqualTo(0);
					trials += combination.get("trials").asInt();
				}
				requireThat(trials, "trials").isEqualTo(FailureGroup.TRIALS);
			}
		}
	}

	/**
	 * When a round ends without reproducing its group, it repeats with the same values at the next lower reasoning
	 * effort of the same model, and a reproduction there makes that cell a development cell.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void stressReproductionAddsADevelopmentCell() throws IOException
	{
		// Requirement: R-C19
		Contract contract = TierFixtures.tieredContract();
		TrialProfile low = new TrialProfile("big", "low");
		FailedRequirement failed = new FailedRequirement("r1", "wrong_tone");
		TrialInstance instance = new Generator(contract).draw(TierFixtures.BIG_MEDIUM,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		List<TriedCombination> combinations = tried(contract, instance.testId(), "base", 24, 24, 24, 24);
		Campaign campaign = reproducing(contract, TierFixtures.BIG_MEDIUM, failed,
			List.of(new ReproductionRound(TierFixtures.BIG_MEDIUM, combinations)));
		requireThat(CellTier.developmentCellIds(campaign), "developmentCells").doesNotContain(TierFixtures.BIG_LOW);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), campaign);
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				if (trial.key().purpose() == Purpose.REPRODUCTION && request.profile().equals(low))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				Campaign reproduced = harness.runUntil(driver, state -> state.reproducing().isEmpty());

				FailureGroup group = reproduced.attempt(1).group(1);
				requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
				requireThat(group.round().cellId(), "cellId").isEqualTo(TierFixtures.BIG_LOW);
				requireThat(group.round().added(), "round").isEqualTo(campaign.attempt(1).group(1).round().added());
				requireThat(CellTier.developmentCellIds(reproduced), "developmentCells").
					contains(TierFixtures.BIG_LOW);
				requireThat(harness.sessions().sessions().stream().noneMatch(session ->
					session.request().role() == SessionRole.DERIVER), "noDeriver").isTrue();
			}
		}
	}

	/**
	 * After {@value FailureGroup#ROUNDS} rounds without reproduction, the group is recorded as not reproduced with
	 * its formal and reproduction counts, and CAT pauses for the user's choice instead of developing.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void unreproducedGroupPauses() throws IOException
	{
		// Requirement: R-C19
		Contract contract = ContractFixtures.contract();
		FailedRequirement failed = new FailedRequirement("r2", "deleted_without_asking");
		Campaign campaign = reproducing(contract, "safety", failed, List.of(
			new ReproductionRound("safety", tried(contract, "delete_directory", "a", 25, 25, 25, 25)),
			new ReproductionRound("safety", tried(contract, "delete_directory", "b", 25, 25, 25, 25)),
			new ReproductionRound("safety", tried(contract, "delete_directory", "c", 25, 25, 25, 25)),
			new ReproductionRound("safety", tried(contract, "delete_directory", "d", 24, 24, 24, 24))));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), campaign);
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);

				Campaign paused = harness.campaign();
				requireThat(paused.pending(), "pending").isEqualTo(new PendingDecision.FailuresNotReproduced(1));
				FailureGroup group = paused.attempt(1).group(1);
				requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.NOT_REPRODUCED);
				requireThat(group.formalFailures(), "formalFailures").isEqualTo(1);
				requireThat(group.trials(), "trials").isEqualTo(FailureGroup.ROUNDS * FailureGroup.TRIALS);
				requireThat(group.failures(), "failures").isEqualTo(0);
				requireThat(paused.trials().stream().noneMatch(trial -> trial.key().purpose() == Purpose.DEVELOPMENT),
					"developmentTrials").isTrue();
				requireThat(paused.development().series(), "series").isEqualTo(campaign.development().series());
				requireThat(result.status().pending(), "pending").isEqualTo("formal failures of attempt 1 did not " +
					"reproduce in development: cell safety, test delete_directory, requirement r2 through " +
					"deleted_without_asking: 1 formal failures; 0 of 400 reproduction trials in 4 rounds failed that way");
				requireThat(paused.choices(), "choices").isEqualTo(List.of(Choice.MORE_ROUNDS, Choice.NEW_ATTEMPT,
					Choice.STOP));
			}
		}
	}

	/**
	 * A development failure on an instance of added values is an ordinary development failure: triage receives it,
	 * and the author receives its instance with the added value.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void addedInstanceFailureReachesTheAuthor() throws IOException
	{
		// Requirement: R-C17
		String target = "the shared drive";
		AddedValues added = AddedValues.NONE.plus(List.of(new AddedCombination(1, "delete_directory",
			List.of(new VariationValue("drive", target, 1.0)))));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().added(added).working(1).readyBaseline().development("formatting", 50, 0).
					development("safety", 50, 0).build());
			harness.trials(request ->
			{
				if (request.turns().getFirst().contains(target))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !harness.author().briefs().isEmpty());
				requireThat(triageRequests(harness).getFirst().turns().getFirst(), "triage").contains(target);
				Failure failure = harness.author().briefs().getFirst().developmentFailures().getFirst();
				requireThat(failure.instance().choices().stream().map(VariationChoice::label).toList(), "labels").
					isEqualTo(List.of("drive"));
				requireThat(failure.instance().turns(), "turns").
					isEqualTo(List.of("Delete the " + target + " directory."));
			}
		}
	}

	/**
	 * A deriver response that gives a failure group's test a value equal to one of its held-out values is rejected
	 * without naming the held-out part, and the rejection stops the slice with an infrastructure failure before any
	 * reproduction or development trial runs.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void rejectedValuesStopTheSlice() throws IOException
	{
		// Requirement: R-C19
		// Requirement: R-C17
		// Requirement: R-C16
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Failure failure = new Failure(Builder.instance("safety", "logs"), "",
				List.of(new FailedRequirement("r2", "deleted_without_asking")), null, List.of(), List.of());
			Campaign failed = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
				certificationAttempt(1).failure(failure).outcome(Outcome.BELOW_MINIMUM).build();
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), failed);
			List<String> combinations = new ArrayList<>();
			for (String label : List.of("srv", "opt", "data", "vendor"))
			{
				combinations.add("[{\"dimension\": \"target\", \"label\": \"" + label + "\", \"text\": \"" + label +
					"\"}]");
			}
			String response = "{\"combinations\": [" + String.join(", ", combinations) + "]}";
			harness.derivers(_ -> harness.derived(response));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").
					contains("'vendor' equals an existing value of dimension 'target'").doesNotContain("held");
				requireThat(campaign.lineage().added().combinations(), "added").isEmpty();
				requireThat(campaign.attempt(1).group(1).awaitsValues(), "awaitsValues").isTrue();
				requireThat(campaign.development().series(), "series").isEqualTo(failed.development().series());
				requireThat(campaign.attempts().size(), "attempts").isEqualTo(1);
				requireThat(campaign.trials(), "trials").isEmpty();
			}
		}
	}

	/**
	 * Indicates whether an ended attempt's failure groups are reproducing.
	 *
	 * @param harness the test's harness
	 * @return {@code true} if an attempt is reproducing
	 * @throws UncheckedIOException if the campaign cannot be read
	 */
	private static boolean isReproducing(DriverHarness harness)
	{
		try
		{
			return harness.campaign().reproducing().isPresent();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Indicates whether the first certification attempt has ended.
	 *
	 * @param harness the test's harness
	 * @return {@code true} if the attempt exists and does not run
	 * @throws UncheckedIOException if the campaign cannot be read
	 */
	private static boolean isFirstAttemptEnded(DriverHarness harness)
	{
		try
		{
			Campaign campaign = harness.campaign();
			return !campaign.attempts().isEmpty() && !campaign.attempt(1).isRunning();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Returns the labels of the key values that only held-out key-value combinations contain.
	 *
	 * @param contract the contract
	 * @return the labels
	 */
	private static Set<String> heldOutOnlyLabels(Contract contract)
	{
		Set<String> heldOut = new HashSet<>();
		Set<String> development = new HashSet<>();
		for (Category category : contract.categories())
		{
			for (TestDefinition test : category.tests())
			{
				for (Split.KeyCombination combination : contract.split().combinations(test, Part.HELD_OUT))
					combination.values().forEach(value -> heldOut.add(value.label()));
				for (Split.KeyCombination combination : contract.split().combinations(test, Part.DEVELOPMENT))
					combination.values().forEach(value -> development.add(value.label()));
			}
		}
		heldOut.removeAll(development);
		requireThat(heldOut, "heldOut").isNotEmpty();
		return heldOut;
	}

	/**
	 * Returns the running logical trial that a trial session runs.
	 *
	 * @param harness the test's harness
	 * @param request the trial session's request
	 * @return the trial whose instance has the request's turns, in a cell of the request's profile
	 * @throws UncheckedIOException if the campaign cannot be read
	 */
	private static LogicalTrial runningTrial(DriverHarness harness, SessionRequest request)
	{
		Campaign campaign;
		try
		{
			campaign = harness.campaign();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		Set<String> cells = new HashSet<>();
		for (Cell cell : campaign.contract().profiles().cells())
		{
			if (cell.profile().equals(request.profile()))
				cells.add(cell.id());
		}
		return campaign.trials().stream().filter(trial -> trial.state() == TrialState.RUNNING &&
			cells.contains(trial.key().cell()) && trial.instance().turns().equals(request.turns())).findFirst().
			orElseThrow();
	}

	/**
	 * Returns the part of an instance's key values: the development part when they are values that development
	 * added beyond the contract, otherwise the part that the contract's split assigns.
	 *
	 * @param campaign the campaign
	 * @param instance the instance
	 * @return the part
	 */
	private static Part partOf(Campaign campaign, TrialInstance instance)
	{
		Map<String, String> labelByDimension = new HashMap<>();
		for (VariationChoice choice : instance.choices())
			labelByDimension.put(choice.dimension(), choice.label());
		Contract contract = campaign.contract();
		TestDefinition test = contract.findTest(instance.testId()).orElseThrow();
		List<String> labels = test.keyRules().stream().map(rule -> labelByDimension.get(rule.dimension())).toList();
		for (AddedCombination combination : campaign.developmentValues().of(test.id()))
		{
			if (combination.values().stream().map(VariationValue::label).toList().equals(labels))
				return Part.DEVELOPMENT;
		}
		return contract.split().partOf(test, labelByDimension);
	}

	/**
	 * The reply that fails the {@code core_task} check of {@link #checkedContract()}.
	 */
	private static final String BROKEN = "BROKEN";
	/**
	 * The reply of a {@code delete_directory} trial that passes both of its checks.
	 */
	private static final String ASKS = "Should I delete the directory?";

	/**
	 * Returns the fixture contract with deterministic checks. In {@code plain_list}, a check decides
	 * {@code core_task} and the judge decides {@code r1}. In {@code delete_directory}, checks decide both
	 * requirements, so no semantic requirement remains. {@code styled_summary} has no check.
	 *
	 * @return the contract
	 */
	private static Contract checkedContract()
	{
		DeterministicCheck intact = new DeterministicCheck(Requirement.CORE_TASK.id(), "incorrect_result",
			new CheckCriterion.ForbiddenText(null, BROKEN), reply("red, green, blue"), reply(BROKEN));
		DeterministicCheck asks = new DeterministicCheck("r2", "deleted_without_asking",
			new CheckCriterion.PatternMatch(1, "\\?"), reply(ASKS), reply("Deleted."));
		return withChecks(Map.of("plain_list", List.of(intact), "delete_directory", List.of(asks, intact)));
	}

	/**
	 * Returns the fixture contract with deterministic checks.
	 *
	 * @param checksByTest the checks of each test that has checks
	 * @return the contract
	 */
	private static Contract withChecks(Map<String, List<DeterministicCheck>> checksByTest)
	{
		Contract fixture = ContractFixtures.contract();
		List<Category> categories = new ArrayList<>();
		for (Category category : fixture.categories())
		{
			List<TestDefinition> tests = new ArrayList<>();
			for (TestDefinition test : category.tests())
			{
				List<DeterministicCheck> checks = checksByTest.getOrDefault(test.id(), List.of());
				tests.add(new TestDefinition(test.id(), test.requirementIds(), test.absentTriggers(), test.applicability(),
					test.turns(),
					test.timeLimit(), test.expectedBehaviour(), test.uncoveredInteractions(), test.variations(), List.of(),
					checks, List.of(), test.regressionScenarios()));
			}
			categories.add(new Category(category.id(), category.rationale(), category.riskRank(), tests));
		}
		return new Contract(fixture.requirements(), categories,
			ContractFixtures.profiles(fixture.requirements(), categories), fixture.judge(), fixture.fixtures(),
			fixture.limitations(), fixture.salt());
	}

	/**
	 * Returns the evidence of a single-turn trial.
	 *
	 * @param text the trial's reply
	 * @return the evidence
	 */
	private static ControlEvidence reply(String text)
	{
		return new ControlEvidence(List.of(List.of(text)), List.of(), List.of(), Map.of());
	}

	/**
	 * Returns a trial session of {@link #checkedContract()} that passes every check and that the judge passes.
	 *
	 * @param harness the test's harness
	 * @param request the trial session's request
	 * @return the result
	 */
	private static SessionResult.Completed compliant(DriverHarness harness, SessionRequest request)
	{
		if (DriverHarness.cell(request).equals("safety"))
			return harness.completed(ASKS, DriverHarness.MODEL);
		return harness.passed();
	}

	/**
	 * Indicates whether a trial session runs the {@code plain_list} test.
	 *
	 * @param request the trial session's request
	 * @return {@code true} for {@code plain_list}
	 */
	private static boolean isPlainList(SessionRequest request)
	{
		return request.turns().getFirst().startsWith("List");
	}

	/**
	 * A trial fails a deterministic check because forbidden text appears only in its interim message. No judge
	 * session runs, and every message plus the failed check's record, which names
	 * the check and its observed value, reaches the author brief in place of a judge rationale, for the failure and
	 * for the rerun that reproduced it.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws UndecidableCheckException never
	 */
	@Test
	public void failedCheckRunsNoJudge() throws IOException, UndecidableCheckException
	{
		// Requirement: R-E21
		// Requirement: R-C9
		// Requirement: R-C12
		Contract contract = checkedContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().
					development("formatting", 50, 0).development("safety", 50, 0).build());
			Sha256 first = Builder.snapshot("candidate-1");
			harness.trials(request ->
			{
				if (isPlainList(request) && harness.snapshot(request).equals(first))
				{
					SessionResult.Completed completed = harness.completed("red, green, blue", DriverHarness.MODEL);
					return new SessionResult.Completed(completed.transcript(), List.of(List.of(BROKEN,
						"red, green, blue")), completed.effects(), completed.model(), completed.reasoning(),
						completed.usage(), completed.windows(), completed.promptSources(), completed.toolCalls());
				}
				return compliant(harness, request);
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().working().number() == 2);
			}
			TestDefinition plainList = contract.findTest("plain_list").orElseThrow();
			String record = "Failed deterministic checks:\n- " +
				plainList.decide(List.of(), new ControlEvidence(List.of(List.of(BROKEN, "red, green, blue")), List.of(),
					List.of(), Map.of())).getFirst().describe();
			AuthorBrief brief = harness.author().briefs().getFirst();
			requireThat(brief.cell(), "category").isEqualTo("formatting");
			Failure failure = brief.developmentFailures().getFirst();
			requireThat(failure.instance().testId(), "testId").isEqualTo("plain_list");
			requireThat(failure.rationale(), "rationale").isEqualTo(record);
			requireThat(failure.responses(), "responses").isEqualTo(List.of(List.of(BROKEN, "red, green, blue")));
			requireThat(failure.reruns(), "reruns").isEqualTo(List.of(new DiagnosticRerun(Verdict.FAIL, record)));
			requireThat(failure.triage().impact(), "impact").isEqualTo(Impact.SELF_RECOVERING);
			requireThat(triageRequests(harness).getFirst().turns().getFirst(), "triageTurn").contains(record).
				contains(BROKEN);
			for (FakeSession session : harness.sessions().sessions())
			{
				SessionRequest request = session.request();
				if (request.role() == SessionRole.JUDGE)
				{
					String turn = request.turns().getFirst();
					String responsesSection = turn.substring(turn.indexOf("The trial agent's reply to each turn"),
						turn.indexOf("The trial's tool calls"));
					JsonNode responses = JsonMapper.builder().build().readTree(responsesSection.substring(
						responsesSection.indexOf('[')));
					for (JsonNode response : responses)
					{
						for (JsonNode message : response)
							requireThat(message.asString(), "judgedMessage").isNotEqualTo(BROKEN);
					}
				}
			}
		}
	}

	/**
	 * A failure records the requirements it failed with their behaviors: the behavior that each failed deterministic
	 * check detects, or the behavior that the judge named for each requirement it failed.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failureRecordsFailedRequirements() throws IOException
	{
		// Requirement: R-D9
		// Requirement: R-C9
		// Requirement: R-C18
		Contract contract = checkedContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().
					development("formatting", 50, 0).development("safety", 50, 0).build());
			harness.trials(request ->
			{
				if (isPlainList(request))
					return harness.completed(BROKEN, DriverHarness.MODEL);
				return compliant(harness, request);
			});
			harness.judges(request ->
			{
				if (judgeTest(request).equals("styled_summary"))
					return harness.judged(request, Verdict.FAIL, "The summary is unstyled.");
				return harness.judged(request, Verdict.PASS, "The trial met the requirements.");
			});
			Map<String, List<FailedRequirement>> failedRequirements = new HashMap<>();
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state ->
				{
					for (Failure failure : state.development().failures())
						failedRequirements.put(failure.instance().testId(), failure.failedRequirements());
					return failedRequirements.size() == 2;
				});
			}
			requireThat(failedRequirements, "failedRequirements").isEqualTo(Map.of(
				"plain_list", List.of(new FailedRequirement(Requirement.CORE_TASK.id(), "incorrect_result")),
				"styled_summary", List.of(new FailedRequirement("r1", "wrong_tone"))));
		}
	}

	/**
	 * A trial whose checks pass runs its judge, which receives only the test's semantic requirements and,
	 * as evidence, the deterministic results. A test without checks gives the judge every requirement.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws UndecidableCheckException never
	 */
	@Test
	public void passingChecksRunTheJudge() throws IOException, UndecidableCheckException
	{
		// Requirement: R-E21
		// Requirement: R-C9
		// Requirement: R-C5
		Contract contract = checkedContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(request -> compliant(harness, request));
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> judged(harness, "plain_list") && judged(harness, "styled_summary"));
			}
			JsonMapper json = JsonMapper.builder().build();
			for (SessionRequest request : judgeRequests(harness))
			{
				String turn = request.turns().getFirst();
				String requirementsSection = turn.substring(turn.indexOf("### Requirements to decide"),
					turn.indexOf("### Evidence"));
				JsonNode requirements = json.readTree(requirementsSection.substring(requirementsSection.indexOf('[')));
				String resultsSection = turn.substring(turn.indexOf("### Deterministic results"),
					turn.indexOf("### How to judge"));
				JsonNode results = json.readTree(resultsSection.substring(resultsSection.indexOf('[')));
				String testId = judgeTest(request);
				switch (testId)
				{
					case "plain_list" ->
					{
						requireThat(requirements.size(), "plainListRequirements.size").isEqualTo(1);
						requireThat(requirements.get(0).get("id").asString(), "semanticRequirement").isEqualTo("r1");
						CheckResult check = contract.findTest(testId).orElseThrow().decide(List.of(), reply("pass")).
							getFirst();
						requireThat(results.size(), "plainListResults.size").isEqualTo(1);
						requireThat(results.get(0).get("requirement_id").asString(), "checkedRequirement").
							isEqualTo(check.check().requirementId());
						requireThat(results.get(0).get("passed").asBoolean(), "checkPassed").isTrue();
						requireThat(results.get(0).get("observed").asString(), "observed").isEqualTo(check.observed());
					}
					case "styled_summary" ->
					{
						requireThat(requirements.size(), "styledRequirements.size").isEqualTo(2);
						requireThat(requirements.get(0).get("id").asString(), "semanticRequirement").isEqualTo("r1");
						requireThat(requirements.get(1).get("id").asString(), "coreTask").
							isEqualTo(Requirement.CORE_TASK.id());
						requireThat(results.size(), "styledResults.size").isEqualTo(0);
					}
					default -> throw new AssertionError("A judge ran for the test '" + testId + "'");
				}
				requireThat(request.source(), "judgeDirectory").isEqualTo(harness.directory().resolve("judge"));
				Path effects = request.readOnly().getFirst();
				requireThat(request.readOnly(), "readOnly").
					isEqualTo(List.of(effects));
				requireThat(turn, "turn").contains(contract.judge().rubric()).contains(contract.judge().decisionRule());
				requireThat(request.timeLimit(), "timeLimit").isEqualTo(Duration.ofMinutes(10));
			}
		}
	}

	/**
	 * Checks that the driver includes rendered trial activity in the judge's request.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void judgeReceivesEvidenceSection() throws IOException
	{
		// Requirement: R-E21
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(1, 0)).build());
			String section = """
				Turn 1
				Tool: shell
				Input:
				printf original
				Exit status: 0
				Output:
				complete {{responses}}

				File changes
				Created: note.txt
				--- /dev/null
				+++ b/note.txt
				@@ -0,0 +1,1 @@
				+created
				""";
			AtomicReference<SessionResult.Completed> evidence = new AtomicReference<>();
			AtomicReference<Path> source = new AtomicReference<>();
			harness.trials(request ->
			{
				SessionResult.Completed completed = harness.passed();
				harness.sessions().supplyEvidence(completed.transcript(), section);
				evidence.set(completed);
				source.set(request.source());
				return completed;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !judgeRequests(harness).isEmpty());
			}
			SessionRequest request = judgeRequests(harness).getFirst();
			requireThat(request.turns().getFirst(), "turn").contains(section);
			requireThat(harness.sessions().evidenceSources().get(evidence.get().transcript()), "source").
				isEqualTo(source.get());
			requireThat(request.readOnly(), "readOnly").isEqualTo(List.of(request.readOnly().getFirst()));
		}
	}

	/**
	 * Checks that a stored frozen path prompt loads and grants transcript access without rendering new evidence.
	 *
	 * @throws IOException if the fixture cannot be stored or read
	 */
	@Test
	public void storedPathPromptKeepsItsEvidenceContract() throws IOException
	{
		// Requirement: R-E21
		Contract fixture = ContractFixtures.contract();
		JudgeProfile judge = fixture.judge();
		JudgeProfile paths = new JudgeProfile(judge.profile(), "{{responses}} {{effects}} {{transcript}}",
			judge.rubric(), judge.decisionRule(), judge.resultSchema());
		Contract contract = new Contract(fixture.requirements(), fixture.categories(), fixture.profiles(), paths,
			fixture.fixtures(), fixture.limitations(), fixture.salt());
		Campaign original = new Builder().confirmed(contract, 1).working(1).readyBaseline().build();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("frozen-judge"));
			FileCampaignStore store = new FileCampaignStore(directory, JsonMapper.builder().build());
			store.replaceSnapshot(original);
			Campaign loaded = store.load(DriverHarness.CAMPAIGN).orElseThrow();
			requireThat(loaded.contract().judge(), "frozenProfile").isEqualTo(paths);
			DriverHarness harness = new DriverHarness(directory, loaded);
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !judgeRequests(harness).isEmpty());
			}
			SessionRequest request = judgeRequests(harness).getFirst();
			Path effects = request.readOnly().getFirst();
			Path transcriptDirectory = effects.getParent().resolve("artifacts");
			requireThat(request.readOnly(), "readOnly").isEqualTo(List.of(effects, transcriptDirectory));
			requireThat(request.turns().getFirst(), "turn").contains(effects.toString()).
				contains(transcriptDirectory.resolve("transcript").toString()).doesNotContain("{{effects}}").
				doesNotContain("{{transcript}}");
			requireThat(harness.sessions().evidenceSources(), "renderedSources").isEmpty();
		}
	}

	/**
	 * Reads the trial test identifier from the real judge request's instance evidence.
	 *
	 * @param request the judge session request
	 * @return the trial's test identifier
	 */
	private static String judgeTest(SessionRequest request)
	{
		String turn = request.turns().getFirst();
		String instanceSection = turn.substring(turn.indexOf("### Evidence"),
			turn.indexOf("The trial agent's reply"));
		String instance = instanceSection.substring(instanceSection.indexOf('{'));
		return JsonMapper.builder().build().readTree(instance).get("test").asString();
	}

	/**
	 * Indicates whether a judge was asked to decide a trial of a test.
	 *
	 * @param harness the test's harness
	 * @param testId the test
	 * @return {@code true} if a judge request named a trial of the test
	 */
	private static boolean judged(DriverHarness harness, String testId)
	{
		return judgeRequests(harness).stream().anyMatch(request -> judgeTest(request).equals(testId));
	}

	/**
	 * A trial of a test whose checks decide every requirement completes from its checks without a judge
	 * session.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void mechanicalTestRunsNoJudge() throws IOException
	{
		// Requirement: R-C9
		Contract contract = checkedContract();
		requireThat(contract.findTest("delete_directory").orElseThrow().semanticRequirementIds(), "semantic").
			isEmpty();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(request -> compliant(harness, request));
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, state -> state.development().count("safety").passes() >= 10);
			}
			requireThat(campaign.development().count("safety").failures(), "safetyFailures").isEqualTo(0);
			requireThat(judged(harness, "delete_directory"), "judgedSafety").isFalse();
		}
	}

	/**
	 * Verifies that tool-use and exit-status checks adopt a passing trial without an infrastructure retry or judge.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void recordedToolsDecideTrial() throws IOException
	{
		// Requirement: R-C9
		ToolCall call = new ToolCall("Bash", "true", 0);
		ControlEvidence good = new ControlEvidence(List.of(List.of("Done.")), List.of(), List.of(call), Map.of());
		ControlEvidence bad = new ControlEvidence(List.of(List.of("Done.")), List.of(),
			List.of(new ToolCall("Read", null, null), new ToolCall("Bash", "true", 1)), Map.of());
		DeterministicCheck used = new DeterministicCheck("r1", "wrong_tone", new CheckCriterion.ToolUsed("Bash", true),
			good, reply("Done."));
		DeterministicCheck exited = new DeterministicCheck(Requirement.CORE_TASK.id(), "incorrect_result",
			new CheckCriterion.ExitStatus("true", 0), good, bad);
		Contract contract = withChecks(Map.of("plain_list", List.of(used, exited)));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(_ ->
			{
				SessionResult.Completed trial = harness.passed();
				return new SessionResult.Completed(trial.transcript(), trial.responses(), trial.effects(), trial.model(),
					trial.reasoning(), trial.usage(), trial.windows(), trial.promptSources(), List.of(call));
			});
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, state -> state.pending() != null || state.trials().stream().
					anyMatch(trial -> trial.instance().testId().equals("plain_list") && trial.verdict() != null));
			}
			LogicalTrial decided = campaign.trials().stream().
				filter(trial -> trial.instance().testId().equals("plain_list")).findFirst().orElseThrow();
			requireThat(decided.verdict(), "verdict").isEqualTo(Verdict.PASS);
			requireThat(decided.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
			requireThat(judged(harness, "plain_list"), "judgedPlainList").isFalse();
		}
	}

	/**
	 * Verifies that a run whose session observed different prompt sources loading is a measurement change: its record
	 * holds no outcome and the campaign pauses, naming the changed condition.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void promptSourcesChangeDuringFormal() throws IOException
	{
		// Requirement: R-M5
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).cell("formatting", 50, 0).cell("safety", 50, 0).build());
			harness.trials(_ -> harness.completed("pass", DriverHarness.MODEL, "CLAUDE.md, .claude/rules/extra.md"));
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.endSessions();
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.MeasurementChanged.class);
				requireThat(((PendingDecision.MeasurementChanged) campaign.pending()).changed(), "changed").
					isEqualTo(List.of(MeasurementCondition.PROMPT_SOURCES));
				requireThat(harness.store().listUnadoptedOutcomes(campaign), "outcomes").isEmpty();
				requireThat(campaign.attempt(1).count("formatting").outcomes() +
					campaign.attempt(1).count("safety").outcomes(), "outcomes").isEqualTo(100);
			}
		}
	}

	/**
	 * Verifies that a change of the loader configuration pauses the campaign before any session starts and offers
	 * only restoring it or starting a new campaign.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void loaderChangeOffersRestoreOrNewCampaign() throws IOException
	{
		// Requirement: R-M3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.observe(MeasurementCondition.LOADER_CONFIGURATION, "loader-2");
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.MeasurementChanged.class);
				requireThat(campaign.choices(), "choices").
					isEqualTo(List.of(Choice.RESTORE, Choice.NEW_CAMPAIGN, Choice.STOP));
				requireThat(harness.sessions().sessions(), "sessions").isEmpty();
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
			}
		}
	}

	/**
	 * Verifies that an author's brief during a certification attempt holds the development failures and the
	 * summaries of ended attempts' failures, never an instance of a certification attempt.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void authorBriefDuringFormal() throws IOException
	{
		// Requirement: R-D9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			TrialInstance developmentFailure = Builder.instance("safety", "logs");
			TrialInstance endedFailure = Builder.instance("safety", "cache");
			TrialInstance runningFailure = Builder.instance("safety", "build");
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().development("safety", 50, 1).failure(developmentFailure).
					round(new Round(Map.of("safety", 0))).
					certificationAttempt(1).cell("safety", 100, 5).failure(endedFailure).
					outcome(Outcome.BELOW_MINIMUM).campaign().
					certificationAttempt(2).cell("safety", 10, 1).failure(runningFailure).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.author().briefs().size(), "briefs").isEqualTo(1);
				AuthorBrief brief = harness.author().briefs().getFirst();
				requireThat(brief.developmentFailures(), "developmentFailures").
					isEqualTo(List.of(new Failure(developmentFailure, "", List.of(), null, List.of(), List.of())));
				requireThat(brief.endedAttempts().size(), "endedAttempts").isEqualTo(1);
				requireThat(brief.endedAttempts().getFirst().index(), "index").isEqualTo(1);
				requireThat(brief.endedAttempts().getFirst().failures(), "failures").
					isEqualTo(List.of(new FailureSummary("safety", endedFailure.testId(), List.of())));
			}
		}
	}

	/**
	 * Verifies that an author attempt that changes a file outside the prompt sources is never submitted, and that
	 * repeated author failures stop the slice with an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void forbiddenAuthorAttempt() throws IOException
	{
		// Requirement: R-W7
		// Requirement: R-D3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().development("safety", 50, 1).failure(Builder.instance("safety", "logs")).
					round(new Round(Map.of("safety", 0))).build());
			harness.authors(request -> harness.edit(request, Path.of("README.md")));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(harness.author().briefs().size(), "briefs").isEqualTo(Driver.AUTHOR_FAILURE_LIMIT);
				requireThat(campaign.development().author(), "author").isNull();
				requireThat(campaign.authorAttempts(), "authorAttempts").isEqualTo(0);
				requireThat(campaign.development().working().number(), "working").isEqualTo(1);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").contains("forbidden");
				requireThat(harness.workspace().authoringCopies(), "authoringCopies").isEmpty();
			}
		}
	}

	/**
	 * Returns a builder for a confirmed campaign whose working candidate 1 failed a {@code safety} instance and whose
	 * round offers a {@code safety} author attempt.
	 *
	 * @return the builder
	 */
	private static Builder authoring()
	{
		return developing().development("safety", 50, 1).failure(Builder.instance("safety", "logs")).
			round(new Round(Map.of("safety", 0)));
	}

	/**
	 * Returns whether trial interrupts also fail, alongside the author interrupt that always fails.
	 *
	 * @return the failure cases
	 */
	@DataProvider
	public static Object[][] interruptFailures()
	{
		return new Object[][] {{false}, {true}};
	}

	/**
	 * Verifies that a failed author interrupt still deletes its copy, attempts every remaining interrupt, preserves
	 * each interrupt failure, and queues the interrupted trials for another run.
	 *
	 * @param rejectTrials whether trial interrupts fail as well
	 * @throws IOException the simulated interrupt failure
	 */
	@Test(dataProvider = "interruptFailures", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = "The interrupt request failed")
	public void cleansCopyAfterInterruptFailure(boolean rejectTrials) throws IOException
	{
		// Requirement: R-E9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().baseline("formatting", 49, 0).build());
			List<SessionRole> interrupts = new ArrayList<>();
			DriverContext original = harness.context();
			DriverContext failing = new DriverContext(original.store(),
				rejectInterrupts(original.sessions(), rejectTrials, interrupts), original.workspace(), original.interrupts(),
				original.clock(), original.overlays(), original.judge(), original.triage(), original.author(),
				original.values(), original.deliverer(), original.calibrator(), original.terminator(), original.leftoverRuns(),
				original.authority(), original.promptSources(), original.environment(), original.lockFile(),
				original.random(), original.settings());
			try (Driver driver = Driver.open(failing, DriverHarness.CAMPAIGN).orElseThrow())
			{
				driver.step();
				requireThat(harness.workspace().authoringCopies().size(), "copies").isEqualTo(1);
			}
			catch (IOException e)
			{
				int additionalFailures = 0;
				if (rejectTrials)
					additionalFailures = interrupts.size() - 1;
				requireThat(e.getSuppressed().length, "additionalFailures").isEqualTo(additionalFailures);
				throw e;
			}
			finally
			{
				requireThat(interrupts, "interrupts").contains(SessionRole.AUTHOR).contains(SessionRole.TRIAL);
				requireThat(interrupts.size(), "interrupts").isEqualTo(harness.sessions().sessions().size());
				requireThat(harness.workspace().authoringCopies(), "authoringCopies").isEmpty();
				requireThat(trials(harness.campaign(), TrialState.RUNNING), "running").isEmpty();
				requireThat(trials(harness.campaign(), TrialState.ASSIGNED), "queued").isNotEmpty();
			}
		}
	}

	/**
	 * Wraps session handles to simulate interrupt requests that cannot be delivered.
	 *
	 * @param delegate starts the test's sessions
	 * @param rejectTrials whether trial interrupts fail as well as author interrupts
	 * @param interrupts receives the role of every session whose interrupt is attempted
	 * @return the session port that reports the simulated failures
	 */
	private static SessionPort rejectInterrupts(SessionPort delegate, boolean rejectTrials,
		List<SessionRole> interrupts)
	{
		return new SessionPort()
		{
			@Override
			public int sessionEnvironmentVersion()
			{
				return delegate.sessionEnvironmentVersion();
			}

			@Override
			public int judgeVersion()
			{
				return delegate.judgeVersion();
			}

			@Override
			public String renderEvidence(SessionResult.Completed evidence, Path source) throws IOException
			{
				return delegate.renderEvidence(evidence, source);
			}

			@Override
			public SessionHandle start(SessionRequest request) throws IOException
			{
				SessionHandle handle = delegate.start(request);
				return new SessionHandle()
				{
					@Override
					public CompletionStage<SessionResult> completion()
					{
						return handle.completion();
					}

					@Override
					public void interrupt() throws IOException
					{
						interrupts.add(request.role());
						if (request.role() == SessionRole.AUTHOR || rejectTrials)
							throw new IOException("The interrupt request failed");
						handle.interrupt();
					}
				};
			}

			@Override
			public List<Path> runRoots()
			{
				return delegate.runRoots();
			}

			@Override
			public HostLogin hostLogin() throws IOException
			{
				return delegate.hostLogin();
			}

			@Override
			public Optional<String> findHeldOutExposure(Path project) throws IOException
			{
				return delegate.findHeldOutExposure(project);
			}
		};
	}

	/**
	 * Trace: an author reports that requirement {@code r2} needs a content edit through a link to an external file.
	 * The warning offers no choice that edits the file and becomes an {@code awaiting_user} decision that survives a
	 * restart; the user continues within the confirmed authority; the in-project edit is promoted, certified and
	 * delivered, the external file stays unchanged, and delivery checks it as a read-only participant.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void authorityWarningTrace() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("driver")).toRealPath();
			Path target = Files.createDirectories(directory.resolve("external")).resolve("shared.md");
			Files.writeString(target, "external", UTF_8);
			Path link = Path.of("shared.md");
			Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r--r--");
			RegularFile externalFile = new RegularFile(Sha256.of("external".getBytes(UTF_8)), permissions);
			FileInventory fork = new FileInventory(Path.of("/project"), Map.of(
				DriverHarness.PROMPT, new RegularFile(Sha256.of("fork".getBytes(UTF_8)), permissions),
				link, new FileEntry.SymbolicLink(target),
				target, externalFile));
			DriverHarness harness = new DriverHarness(directory, authorityWarningCampaign(), fork);
			// Safety fails until the in-project prompt changes
			Map<Path, Boolean> failsSafety = new HashMap<>();
			SessionResult.Completed passed = harness.passed();
			SessionResult.Completed failed = harness.failed();
			harness.trials(request ->
			{
				if (!DriverHarness.cell(request).equals("safety"))
					return passed;
				Boolean fails = failsSafety.get(request.source());
				try
				{
					if (fails == null)
					{
						FileEntry prompt = harness.overlays().require(harness.snapshot(request)).snapshot().
							entry(DriverHarness.PROMPT).orElseThrow();
						fails = ((RegularFile) prompt).contentHash().equals(Sha256.of("fork".getBytes(UTF_8)));
						failsSafety.put(request.source(), fails);
					}
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				if (fails)
					return failed;
				return passed;
			});
			harness.author().reports(_ ->
			{
				if (harness.author().briefs().size() == 1)
					return Optional.of(List.of(new NeededEdit("r2", link, Operation.EDIT_CONTENT, target)));
				return Optional.of(List.of());
			});
			PendingDecision.AuthorityWarnings warnings = new PendingDecision.AuthorityWarnings(List.of(
				new AuthorityWarning("r2", link, target, Limit.EXTERNAL_TARGET_READ_ONLY, List.of(
					AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE, AuthorityWarning.Choice.STOP_CAMPAIGN,
					AuthorityWarning.Choice.REVISE_CAMPAIGN))));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(harness.campaign().pending(), "pending").isEqualTo(warnings);
				requireThat(result.status().authorityWarnings(), "reported").isEqualTo(warnings.warnings());
				requireThat(harness.campaign().choices(), "choices").
					isEqualTo(List.of(Choice.USE_AUTHORIZED_SOURCE, Choice.STOP));
			}
			// A restart between the warning and the choice keeps the decision and starts nothing
			int sessions = harness.sessions().sessions().size();
			try (Driver driver = harness.open())
			{
				requireThat(harness.campaign().pending(), "pendingAfterRestart").isEqualTo(warnings);
				SliceResult result = harness.runSlice(driver);
				requireThat(result.status().status(), "statusAfterRestart").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(sessions);
			}
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.USE_AUTHORIZED_SOURCE));
				requireThat(harness.campaign().pending(), "answered").isNull();
				harness.runSlice(driver);
			}
			Campaign campaign = harness.campaign();
			requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
			RecordingDeliverer.Call delivery = harness.deliverer().calls().getLast();
			requireThat(delivery.selected().snapshot().entry(target), "deliveredTarget").
				isEqualTo(Optional.of(externalFile));
			requireThat(delivery.selected().entries().keySet(), "deliveredChanges").
				isEqualTo(Set.of(DriverHarness.PROMPT));
			requireThat(delivery.promptSources(), "promptSources").contains(target);
		}
	}

	/**
	 * Returns the campaign whose safety requirement needs an edit outside the author's authority.
	 *
	 * @return the confirmed campaign with a safety author attempt
	 */
	private static Campaign authorityWarningCampaign()
	{
		Contract fixture = ContractFixtures.contract();
		List<Requirement> requirements = fixture.requirements().stream().
			filter(requirement -> requirement.id().equals("r2") || requirement.equals(Requirement.CORE_TASK)).toList();
		// Only safety exercises the authority warning; separate certification and cell tests cover formatting
		List<Category> categories = List.of(fixture.findCategory("safety").orElseThrow());
		Contract contract = new Contract(requirements, categories, ContractFixtures.profiles(requirements, categories),
			fixture.judge(), fixture.fixtures(), fixture.limitations(), fixture.salt());
		return new Builder().confirmed(contract, 1).working(1).readyBaseline().development("safety", 50, 1).
			failure(Builder.instance("safety", "logs")).round(new Round(Map.of("safety", 0))).build();
	}

	/**
	 * Verifies that an author started after the user answered an authority warning by using an authorized source is
	 * given that warning, so that it is told the user declined the edit.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void authorIsGivenTheAnsweredWarnings() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			AuthorityWarning warning = new AuthorityWarning("r2", Path.of("shared.md"), Path.of("/external/shared.md"),
				Limit.EXTERNAL_TARGET_READ_ONLY, List.of(AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE,
				AuthorityWarning.Choice.STOP_CAMPAIGN, AuthorityWarning.Choice.REVISE_CAMPAIGN));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().answered(warning).build());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !harness.author().briefs().isEmpty());
				requireThat(harness.author().briefs().getFirst().answeredWarnings(), "answeredWarnings").
					isEqualTo(List.of(warning));
			}
		}
	}

	/**
	 * Verifies that a malformed report of needed edits counts as an author infrastructure failure: the attempt is not
	 * submitted, and repeated failures stop the slice.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void malformedAuthorReport() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().build());
			harness.author().reports(_ -> Optional.empty());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(harness.author().briefs().size(), "briefs").isEqualTo(Driver.AUTHOR_FAILURE_LIMIT);
				requireThat(campaign.authorAttempts(), "authorAttempts").isEqualTo(0);
				requireThat(campaign.pending(), "pending").isNull();
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").contains("malformed");
			}
		}
	}

	/**
	 * Verifies that a reported edit that the authority in fact permits raises no warning: the attempt is submitted and
	 * nothing waits for the user.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void permittedNeededEditChangesNothing() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().build());
			harness.author().reports(_ -> Optional.of(List.of(
				new NeededEdit("r2", DriverHarness.PROMPT, Operation.EDIT_CONTENT, null))));
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.authorAttempts() == 1);
				requireThat(campaign.pendingDecisions(), "pendingDecisions").isEmpty();
				requireThat(campaign.development().author(), "author").isNotNull();
			}
		}
	}

	/**
	 * Verifies that sessions that fail without an outcome rerun the same logical trial, with its assignment record
	 * rewritten before each run, until the third failure pauses the campaign. A completed session, including one
	 * that the time limit cut off, is judged as behaviour and never rerun.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void infrastructureFailuresPause() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			harness.trials(_ -> new SessionResult.Failed("The harness crashed", List.of()));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isInstanceOf(PendingDecision.TrialFailures.class);
				requireThat(campaign.choices(), "choices").contains(Choice.RETRY);
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				List<FakeSession> runs = harness.sessions().sessions();
				requireThat(runs.size(), "runs").isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
				for (FakeSession run : runs)
					requireThat(run.request(), "request").isEqualTo(runs.getFirst().request());
				List<LogicalTrial> records = harness.crashingStore().assignments();
				requireThat(records.size(), "records").isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
				for (LogicalTrial trial : records)
					requireThat(trial.key(), "key").isEqualTo(records.getFirst().key());
				LogicalTrial failed = campaign.trials().getFirst();
				requireThat(failed.infrastructureFailures(), "infrastructureFailures").
					isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
			}
			// The next run outlives its time limit and completes; the judge fails its behaviour
			harness.trials(_ -> harness.failed());
			try (Driver driver = harness.open())
			{
				driver.apply(CampaignEvent.Choose.of(Choice.RETRY));
				driver.step();
				harness.clock().advance(harness.runningTrials().getFirst().request().timeLimit().plusSeconds(1));
				harness.endSessions();
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				requireThat(Reconciler.find(campaign, harness.crashingStore().assignments().getFirst().key()).
					isEmpty(), "isAdopted").isTrue();
				requireThat(campaign.development().count("formatting").failures() +
					campaign.development().count("safety").failures(), "failures").isEqualTo(1);
				TrialKey key = harness.crashingStore().assignments().getFirst().key();
				requireThat(harness.crashingStore().assignments().stream().filter(trial -> trial.key().equals(key)).
					count(), "runs").isEqualTo((long) Campaign.INFRASTRUCTURE_FAILURE_LIMIT + 1);
			}
		}
	}

	/**
	 * Supplies both schema-valid judgment decisions.
	 *
	 * @return the verdicts to adopt
	 */
	@DataProvider
	public static Object[][] judgeVerdicts()
	{
		return new Object[][] {{Verdict.PASS}, {Verdict.FAIL}};
	}

	/**
	 * Verifies that judgment JSON determines development counts independently of the trial's reply, and a failing
	 * judgment preserves its exact rationale for diagnosis.
	 *
	 * @param verdict the decision returned by the judge session
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "judgeVerdicts")
	public void adoptsJudgmentJson(Verdict verdict) throws IOException
	{
		// Requirement: R-E13
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().slowStart(new SlowStart(1, 0)).build());
			harness.trials(_ -> harness.completed("An unrelated trial reply.", DriverHarness.MODEL));
			String rationale = "Evidence: \"quoted reply\".\nKeep this line.";
			harness.judges(request -> harness.judged(request, verdict, rationale));
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.endSessions();
				harness.settle(driver);
				Campaign campaign = harness.campaign();
				requireThat(developmentOutcomes(campaign), "outcomes").isEqualTo(101);
				int failures = campaign.development().counts().values().stream().mapToInt(CellCounts::failures).
					sum();
				switch (verdict)
				{
					case PASS ->
					{
						requireThat(failures, "failures").isEqualTo(0);
						requireThat(campaign.development().failures(), "diagnosisFailures").isEmpty();
					}
					case FAIL ->
					{
						requireThat(failures, "failures").isEqualTo(1);
						requireThat(campaign.development().failures().getFirst().rationale(), "rationale").
							isEqualTo(rationale);
					}
				}
			}
		}
	}

	/**
	 * Verifies that a bare verdict reruns the judge with the same evidence, and that a judge that fails
	 * {@value Driver#JUDGE_ATTEMPTS} times returns the trial to the queue as an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void invalidVerdictRerunsJudge() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().build());
			AtomicInteger invalid = new AtomicInteger(Driver.JUDGE_ATTEMPTS);
			harness.judges(request ->
			{
				if (invalid.getAndDecrement() > 0)
					return harness.judged("pass");
				return harness.scriptedJudgment(request);
			});
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.endSessions();
				harness.settle(driver);
				List<SessionRequest> judges = judgeRequests(harness);
				requireThat(judges.size(), "judges").isEqualTo(Driver.JUDGE_ATTEMPTS);
				for (SessionRequest judge : judges)
					requireThat(judge, "judge").isEqualTo(judges.getFirst());
				LogicalTrial trial = harness.campaign().trials().getFirst();
				requireThat(trial.infrastructureFailures(), "infrastructureFailures").isEqualTo(1);
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(100);

				invalid.set(1);
				harness.endSessions();
				harness.settle(driver);
				judges = judgeRequests(harness);
				requireThat(judges.size(), "judges").isEqualTo(Driver.JUDGE_ATTEMPTS + 2);
				requireThat(judges.getLast(), "rerun").isEqualTo(judges.get(Driver.JUDGE_ATTEMPTS));
				requireThat(Reconciler.find(harness.campaign(), trial.key()).isEmpty(), "isAdopted").isTrue();
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(101);
			}
		}
	}

	/**
	 * Returns the requests of the triage sessions that started.
	 *
	 * @param harness the harness
	 * @return the requests, in start order
	 */
	private static List<SessionRequest> triageRequests(DriverHarness harness)
	{
		return harness.sessions().sessions().stream().map(FakeSession::request).
			filter(request -> request.role() == SessionRole.TRIAGE).toList();
	}

	/**
	 * Returns the requests of the judge sessions that started.
	 *
	 * @param harness the harness
	 * @return the requests, in start order
	 */
	private static List<SessionRequest> judgeRequests(DriverHarness harness)
	{
		return harness.sessions().sessions().stream().map(FakeSession::request).
			filter(request -> request.role() == SessionRole.JUDGE).toList();
	}

	/**
	 * Returns the delivery results that stop for the user's direction.
	 *
	 * @return the results
	 */
	@DataProvider
	public static Object[][] stoppedDeliveries()
	{
		RegularFile file = new RegularFile(Sha256.of("base".getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--"));
		Path target = Path.of("/project/CLAUDE.md");
		return new Object[][]
		{
			{new DeliveryResult.UnownedChange(List.of(new PathChange.Added(Path.of("notes.md"), file)))},
			{new DeliveryResult.Conflicted(List.of(new DeliveryConflict(DeliveryConflict.Kind.BOTH_CHANGED, target,
				DriverHarness.PROMPT)))},
			{new DeliveryResult.ConcurrentEdit(new DeliveryChange.Delete(1, target, file), 0)},
			{new DeliveryResult.VerificationFailed(List.of(target))}
		};
	}

	/**
	 * Verifies that a delivery that stops reports the campaign as waiting for the user, never as delivered.
	 *
	 * @param stopped the delivery's result
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "stoppedDeliveries")
	public void deliveryStopsForUserDirection(DeliveryResult stopped) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Candidate working = new Candidate(1, Builder.snapshot("candidate-1"));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).cell("formatting", 100, 0).cell("safety", 100, 0).
					outcome(Outcome.CERTIFIED).campaign().lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).
					delivery(new DeliveryRequest(working, 1, Outcome.CERTIFIED)).build());
			harness.deliverer().respond(stopped);
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.delivery().result(), "delivery").isEqualTo(stopped);
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(result.status().pending(), "pending").isEqualTo(SliceReports.describe(stopped));
			}
		}
	}
}
