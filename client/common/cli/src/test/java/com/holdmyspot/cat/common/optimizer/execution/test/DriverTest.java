package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.AttemptResult;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorApproach;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Candidate;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.Baseline;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.ConcurrencyPolicy;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing;
import com.holdmyspot.cat.common.optimizer.campaign.DecisionTable.CellDecision;
import com.holdmyspot.cat.common.optimizer.campaign.DecisionTable;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Development;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.EditOrContinue;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.campaign.FailureSummary;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.OptimizationEvidence;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionRun;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.PairCounts;
import com.holdmyspot.cat.common.optimizer.campaign.CommitGate;
import com.holdmyspot.cat.common.optimizer.campaign.ReproductionRound;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.SlowStart;
import com.holdmyspot.cat.common.optimizer.campaign.TestCaseOutcomes;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisionRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.TriedTestCase;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.test.Boundaries;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCases;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.CertificationScope;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.DrawStream;
import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.JudgeProfile;
import com.holdmyspot.cat.common.optimizer.contract.JudgedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.MappedAssertion;
import com.holdmyspot.cat.common.optimizer.contract.OnToolFailure;
import com.holdmyspot.cat.common.optimizer.contract.Part;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.contract.TestCase;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveredTest;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.execution.CampaignLock;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.DriverContext;
import com.holdmyspot.cat.common.optimizer.execution.NeededEdit;
import com.holdmyspot.cat.common.optimizer.execution.Reconciler;
import com.holdmyspot.cat.common.optimizer.execution.SliceReports;
import com.holdmyspot.cat.common.optimizer.execution.SliceResult;
import com.holdmyspot.cat.common.optimizer.execution.TrialSelection;
import com.holdmyspot.cat.common.optimizer.execution.SliceRunner;
import com.holdmyspot.cat.common.optimizer.execution.SliceSettings;
import com.holdmyspot.cat.common.optimizer.execution.Step;
import com.holdmyspot.cat.common.optimizer.execution.fixture.test.FaultingFileChannel;
import com.holdmyspot.cat.common.optimizer.inventory.Authority;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryConflict;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Limit;
import com.holdmyspot.cat.common.optimizer.inventory.Operation;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.OverlayEntry.Write;
import com.holdmyspot.cat.common.optimizer.inventory.PathChange;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.HostLogin;
import com.holdmyspot.cat.common.optimizer.port.HookInvocation;
import com.holdmyspot.cat.common.optimizer.port.HookRegistration;
import com.holdmyspot.cat.common.optimizer.port.LoadedSources;
import com.holdmyspot.cat.common.optimizer.port.UnreachableReference;
import com.holdmyspot.cat.common.optimizer.port.PluginBase;
import com.holdmyspot.cat.common.optimizer.port.PluginRemovals;
import com.holdmyspot.cat.common.optimizer.port.RateLimitWindow;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.SourceSelection;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;
import com.holdmyspot.cat.common.optimizer.report.CellReport;
import com.holdmyspot.cat.common.optimizer.report.OptimizationReport.InputComparison;
import com.holdmyspot.cat.common.optimizer.report.ReportEvidence;
import com.holdmyspot.cat.common.optimizer.report.ReportStatus;
import com.holdmyspot.cat.common.optimizer.report.Status;
import com.holdmyspot.cat.common.optimizer.report.StatusReport;
import com.holdmyspot.cat.common.optimizer.report.TokenStatistics;
import com.holdmyspot.cat.common.optimizer.statistics.ComparisonBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.PairedComparison;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;
import com.holdmyspot.cat.common.optimizer.statistics.PairTally;
import com.holdmyspot.cat.common.optimizer.statistics.SignificanceLevel;
import com.holdmyspot.cat.common.optimizer.store.FileCampaignStore;
import com.holdmyspot.cat.common.optimizer.store.TrialSession;
import com.holdmyspot.cat.common.runner.HarnessCapacity.Hold;
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
import java.util.LinkedHashMap;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
					completed.initialProject(),
					completed.model(), completed.reasoning(), new TokenUsage(80, 0, 0, 0), completed.windows(),
					completed.promptSources(), completed.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.contract().cellIds().stream().
					allMatch(cell -> state.baseline().count(cell).outcomes() > 0 &&
						state.development().count(cell).outcomes() > 0));
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
					judgment.initialProject(),
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
	 * Reloaded status retains trial, judge and prompt author usage, including rejected prompt author outputs.
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
				original.promptAuthor(), original.values(), original.deliverer(), original.calibrator(), original.terminator(),
				original.leftoverRuns(), original.authority(), original.promptSources(), original.environment(),
				original.lockFile(), original.random(), original.settings(), original.json());
			Map<SessionRole, List<TokenUsage>> expected = new EnumMap<>(SessionRole.class);
			harness.trials(_ -> withUsage(harness.failed(), SessionRole.TRIAL, expected));
			AtomicInteger judgments = new AtomicInteger();
			harness.judges(request ->
			{
				if (judgments.getAndIncrement() == 0)
					return withUsage(harness.judged("{}"), SessionRole.JUDGE, expected);
				return withUsage(harness.scriptedJudgment(request), SessionRole.JUDGE, expected);
			});
			harness.promptAuthors(request -> withUsage((SessionResult.Completed) harness.edit(request,
				Path.of("README.md")), SessionRole.PROMPT_AUTHOR, expected));
			try (Driver driver = Driver.open(context, DriverHarness.CAMPAIGN).orElseThrow())
			{
				SliceResult result = harness.runSlice(driver);
				TokenStatistics reported = StatusReport.of(new FileCampaignStore(directory, JsonMapper.builder().build()).
					load(DriverHarness.CAMPAIGN).orElseThrow(), ReportEvidence.NONE).tokens();
				requireThat(reported, "durableUsage").isEqualTo(TokenStatistics.of(expected));
				requireThat(result.status().tokens(), "sliceUsage").isEqualTo(reported);
				for (SessionRole role : List.of(SessionRole.TRIAL, SessionRole.JUDGE, SessionRole.PROMPT_AUTHOR))
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
			completed.initialProject(),
			completed.model(), completed.reasoning(), usage, completed.windows(), completed.promptSources(),
			completed.toolCalls());
	}

	/**
	 * Weak prompt authors receive identical evidence after older development trials settle, including when capacity
	 * delays the third attempt.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void weakAuthorsWaitForDevelopment() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("prompt-author-briefs")),
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
				requireThat(harness.promptAuthor().briefs(), "authorsBeforeDevelopmentSettles").isEmpty();

				held.end(harness.passed());
				harness.settle(driver);
				requireThat(harness.running().size(), "sharedCapacity").isLessThanOrEqualTo(2);
				harness.runUntil(driver, _ -> harness.promptAuthor().briefs().size() == 3);
				for (PromptAuthorBrief brief : harness.promptAuthor().briefs())
				{
					requireThat(brief.developmentFailures(), "sameFailures").
						isEqualTo(harness.promptAuthor().briefs().getFirst().developmentFailures());
					requireThat(brief.developmentCounts(), "sameCounts").
						isEqualTo(harness.promptAuthor().briefs().getFirst().developmentCounts());
				}
				List<SessionRequest> promptAuthors = harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.PROMPT_AUTHOR).toList();
				requireThat(promptAuthors.get(0).turns().getFirst(), "firstApproach").
					contains("Correct the instruction where it is stated:");
				requireThat(promptAuthors.get(1).turns().getFirst(), "secondApproach").
					contains("Rewrite rather than add:");
				requireThat(promptAuthors.get(2).turns().getFirst(), "thirdApproach").
					contains("Add the missing guidance where the failing sessions make the decision,");
				requireThat(harness.campaign().development().counts().values().stream().
					mapToLong(CellCounts::passes).sum(), "settledDevelopmentPasses").isEqualTo(21);
			}
		}
	}

	/**
	 * A blocking development failure discards the working candidate's other development trial, interrupting its
	 * session without recording a failure, and starts the round's prompt authors at once, without diagnostic reruns and
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
				requireThat(harness.promptAuthor().briefs(), "prompt_authors").isNotEmpty();
				requireThat(harness.promptAuthor().briefs().getFirst().developmentFailures(), "briefFailures").
					isEqualTo(List.of(failure));
				long trialSessions = harness.sessions().sessions().stream().
					filter(session -> session.request().role() == SessionRole.TRIAL).count();
				requireThat(trialSessions, "trialSessions").isEqualTo(2L);
			}
		}
	}

	/**
	 * Recording a passing trial's verdict deletes the trial's effects and its copy of the initial project.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void passingTrialDeletesProjectCopies() throws IOException
	{
		// Requirement: R-W14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("passing-copies")),
				new Builder().confirmed().working(1).readyBaseline().development("safety", 10, 0).
					slowStart(new SlowStart(1, 0)).build());
			AtomicReference<SessionResult.Completed> first = new AtomicReference<>();
			harness.trials(_ ->
			{
				SessionResult.Completed completed = harness.passed();
				first.compareAndSet(null, completed);
				return completed;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().count("safety").outcomes() > 10);

				requireThat(Files.notExists(first.get().effects()), "effectsDeleted").isTrue();
				requireThat(Files.notExists(first.get().initialProject()), "initialProjectDeleted").isTrue();
			}
		}
	}

	/**
	 * Recording a failed trial's verdict keeps the trial's effects and its copy of the initial project, which later
	 * render the failure's evidence.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void failedTrialKeepsProjectCopies() throws IOException
	{
		// Requirement: R-W14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("failed-copies")),
				new Builder().confirmed().working(1).readyBaseline().development("safety", 10, 0).
					slowStart(new SlowStart(1, 0)).build());
			harness.triages(_ -> harness.triaged(Impact.BLOCKING));
			AtomicReference<SessionResult.Completed> first = new AtomicReference<>();
			harness.trials(_ ->
			{
				SessionResult.Completed completed = harness.failed();
				first.compareAndSet(null, completed);
				return completed;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> !state.development().failures().isEmpty());

				requireThat(first.get().effects(), "effects").isDirectory();
				requireThat(first.get().initialProject(), "initialProject").isDirectory();
			}
		}
	}

	/**
	 * A prompt author started after a failed trial is given that trial's section, rendered from the trial's retained
	 * transcript and effects against a staged copy of the working candidate.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void promptAuthorIsGivenTheFailedTrialSection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("failure-section")),
				new Builder().confirmed().working(1).readyBaseline().development("safety", 10, 0).
					slowStart(new SlowStart(1, 0)).build());
			harness.triages(_ -> harness.triaged(Impact.BLOCKING));
			SessionEvidence activity = new SessionEvidence(List.of(new SessionEvidence.Tool(1,
				SessionEvidence.ROOT_AGENT, "shell", "read-file docs/handbok.md", true, 127,
				"bash: read-file: command not found\n")));
			AtomicReference<SessionResult.Completed> failed = new AtomicReference<>();
			harness.trials(_ ->
			{
				SessionResult.Completed completed = harness.failed();
				try
				{
					Files.createDirectories(completed.transcript().getParent());
					Files.writeString(completed.transcript(), "", UTF_8);
					Files.createDirectories(completed.effects());
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				harness.sessions().supplyEvidence(completed.transcript(), activity);
				failed.set(completed);
				return completed;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !harness.promptAuthor().briefs().isEmpty());

				PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
				requireThat(brief.evidenceSections().keySet(), "evidenceSections").
					isEqualTo(Set.of(brief.developmentId(0)));
				requireThat(brief.evidenceSections().get(brief.developmentId(0)), "section").
					startsWith("Transcript: " + failed.get().transcript() + "\nEffects directory: " +
						failed.get().effects() + "\n\n" + activity.render()).
					contains("\nFile changes\n");
			}
		}
	}

	/**
	 * A prompt author started after a failed trial whose transcript or effects are no longer retained is given no
	 * section for that failure.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void promptAuthorGetsNoUnretainedTrialSection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("failure-section")),
				new Builder().confirmed().working(1).readyBaseline().development("safety", 10, 0).
					slowStart(new SlowStart(1, 0)).build());
			// The failed trial's effects stop being retained after its triage
			List<Path> effects = new ArrayList<>();
			harness.triages(_ ->
			{
				try
				{
					for (Path directory : effects)
						TemporaryDirectories.deleteRecursively(directory);
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				return harness.triaged(Impact.BLOCKING);
			});
			harness.trials(_ ->
			{
				SessionResult.Completed completed = harness.failed();
				effects.add(completed.effects());
				try
				{
					Files.createDirectories(completed.transcript().getParent());
					Files.writeString(completed.transcript(), "", UTF_8);
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				harness.sessions().supplyEvidence(completed.transcript(), new SessionEvidence(List.of()));
				return completed;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !harness.promptAuthor().briefs().isEmpty());

				PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
				requireThat(brief.developmentFailures().size(), "failures").isEqualTo(1);
				requireThat(brief.evidenceSections(), "evidenceSections").isEmpty();
			}
		}
	}

	/**
	 * Returns the rationale of a judgment that fails the assertion {@code asks_before_deleting} and passes
	 * {@code expected_outcome}, giving the same reason for both.
	 *
	 * @param reason the judge's reason for each assertion
	 * @return the rationale
	 */
	private static String deletionFailureRationale(String reason)
	{
		return "- `asks_before_deleting`: fail (deleted_without_asking). " + reason + "\n- `expected_outcome`: pass. " +
			reason;
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
					isEqualTo(deletionFailureRationale("The trial did not meet the requirements."));
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
					return new SessionResult.RateLimited(new Hold(resetsAt.get(), 0), List.of());
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
	 * evidence, and returns to one prompt author at a time after the new candidate's development estimate improves.
	 * <p>
	 * The test takes over a second under a parallel build: proving the claim needs a campaign that runs three
	 * attempts' promotion trials and then minimality to completion, and each of its steps rebuilds and validates the
	 * campaign state.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void weakRoundSelectsBestThenRunsSequentially() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(
				cleanup.add(Files.createTempDirectory("concurrent-prompt-authors")),
				new Builder().confirmed().working(1).readyBaseline().
					regression(Builder.targeted("formatting", "formal"), Verdict.FAIL).build());
			Sha256 working = Builder.snapshot("candidate-1");
			harness.overlays().put(working, harness.candidate("first\nseparator\nlast\n"));
			harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
			AtomicInteger authored = new AtomicInteger();
			harness.promptAuthors(request ->
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
				requireThat(harness.promptAuthor().briefs().size(), "concurrentAttempts").isEqualTo(3);
				requireThat(harness.promptAuthor().briefs().get(1).developmentFailures(), "sameFailures").
					isEqualTo(harness.promptAuthor().briefs().get(0).developmentFailures());
				requireThat(harness.promptAuthor().briefs().get(2).developmentCounts(), "sameCounts").
					isEqualTo(harness.promptAuthor().briefs().get(0).developmentCounts());
				requireThat(harness.running().stream().filter(session ->
					session.request().role() == SessionRole.PROMPT_AUTHOR).count(), "runningAuthors").isEqualTo(3);

				Campaign promoted = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Overlay minimal = harness.overlays().require(promoted.development().working().snapshot());
				Write selected = (Write) minimal.entries().get(DriverHarness.PROMPT);
				requireThat(new String(selected.content().bytes(), UTF_8), "minimalWinner").
					isEqualTo("needed-two\nseparator\nlast\n");
				requireThat(promoted.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				requireThat(promoted.development().counts(), "newCounts").isEmpty();
				requireThat(promoted.attempts(), "allowance").isEmpty();
				ObjectNode status = StatusReport.of(promoted, ReportEvidence.NONE).toJson(JsonMapper.builder().build());
				JsonNode attempts = status.get("prompt_author_attempts");
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
				requireThat(harness.promptAuthor().briefs().size(), "nextRoundAttempts").isEqualTo(4);
				requireThat(harness.running().stream().filter(session ->
					session.request().role() == SessionRole.PROMPT_AUTHOR).count(), "strongRoundAuthors").isEqualTo(1);
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
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("restart-prompt-authors")),
				new Builder().confirmed().working(1).readyBaseline().build());
			harness.trials(_ -> harness.failed());
			List<TrialKey> pending;
			Map<String, CellCounts> counts;
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().round() != null);
				requireThat(harness.promptAuthor().briefs().size(), "attempts").isEqualTo(3);
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
				requireThat(promoted.promptAuthorAttempts(), "attempts").isEqualTo(3);
				requireThat(promoted.development().counts(), "newCandidateCounts").isEmpty();
				requireThat(promoted.attempts(), "allowance").isEmpty();
				requireThat(harness.promptAuthor().briefs().size(), "noRepeatedAuthors").isEqualTo(3);
			}
		}
	}

	/**
	 * All three weak attempts use one working reference's verdict, and that run does not count as their own evidence.
	 *
	 * @param referenceVerdict the working candidate's verdict on the regression test case
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
					if (state.development().promptAuthor() != null)
					{
						for (PromotionRun run : state.development().promptAuthor().runs())
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
				requireThat(harness.promptAuthor().briefs().size(), "unchangedPromptAuthorAttempts").isEqualTo(3);
			}
		}
	}

	/**
	 * Creates a weak round whose three edits fix its triggering failure but fail a test case with no working outcome.
	 *
	 * @param directory the fixture directory owned by the calling test
	 * @param referenceVerdict the working candidate's verdict on the failed regression test case
	 * @return the harness with scripted trial outcomes
	 * @throws IOException if the fixture cannot be used
	 */
	private static DriverHarness workingReferenceHarness(Path directory, Verdict referenceVerdict) throws IOException
	{
		Failure failure = new Failure(Builder.targeted("safety", "logs"), "representative failure", List.of(), null,
			List.of(new DiagnosticRerun(Verdict.FAIL, "reproduced failure")), List.of(), Builder.FILES);
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
	 * The second sequential reservation uses the rewrite approach, including when its prompt author session is resumed.
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
				harness.runUntil(driver, _ -> harness.promptAuthor().briefs().size() == 2);
				SessionRequest second = harness.sessions().sessions().getLast().request();
				requireThat(second.role(), "role").isEqualTo(SessionRole.PROMPT_AUTHOR);
				requireThat(second.turns().getFirst(), "secondApproach").contains("Rewrite rather than add:");
			}

			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.promptAuthor().briefs().size(), "resumedPromptAuthor").isEqualTo(3);
				SessionRequest resumed = harness.sessions().sessions().getLast().request();
				requireThat(resumed.role(), "role").isEqualTo(SessionRole.PROMPT_AUTHOR);
				requireThat(resumed.turns().getFirst(), "resumedApproach").contains("Rewrite rather than add:");
				requireThat(harness.campaign().development().round().reservations().size(), "reservations").
					isEqualTo(2);
			}
		}
	}

	/**
	 * A cell whose earlier round made two prompt author attempts gives its next attempt the third approach rather than
	 * starting the list again.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void approachContinuesFromEarlierRound() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			List<PromptAuthorDiagnosis> earlier = new ArrayList<>();
			for (int number = 1; number <= 2; ++number)
			{
				earlier.add(new PromptAuthorDiagnosis(number, 1, "safety", Builder.snapshot("candidate-1"), List.of(),
					List.of(), List.of(), Path.of("prompt-author-" + number, "transcript.jsonl"),
					Path.of("prompt-author-" + number, "effects")));
			}
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("approach-rotation")),
				authoring().promptAuthorAttempts(2).
					optimization(new OptimizationEvidence(earlier, List.of(), Map.of(), Map.of())).build());
			harness.trials(_ -> harness.failed());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !harness.promptAuthor().briefs().isEmpty());
				requireThat(harness.promptAuthor().briefs().getFirst().approach(), "approach").
					isEqualTo(PromptAuthorApproach.values()[2]);
			}
		}
	}

	/**
	 * An attempt fixing one of two triggering test cases becomes the working candidate after unnecessary hunks
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
			harness.promptAuthors(request -> harness.edit(request, DriverHarness.PROMPT,
				"needed\nseparator\nunneeded\n"));
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
				Campaign result = harness.runUntil(driver, state -> state.development().working().number() == 2);
				requireThat(result.development().working().number(), "workingCandidate").isEqualTo(2);
				Write entry = (Write) harness.overlays().require(result.development().working().snapshot()).
					entries().get(DriverHarness.PROMPT);
				requireThat(new String(entry.content().bytes(), UTF_8), "minimalPrompt").
					isEqualTo("needed\nseparator\nlast\n");
				requireThat(result.optimization().removed(), "removed").isEqualTo(List.of("CLAUDE.md#2"));
				JsonNode attempts = StatusReport.of(result, ReportEvidence.NONE).toJson(JsonMapper.builder().build()).
					get("prompt_author_attempts");
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
					new DiagnosticRerun(Verdict.FAIL, "fail")), List.of(), Builder.FILES);
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("extended-resume")),
				new Builder().confirmed().working(1).readyBaseline().ceiling(1).development("safety", 60, 1).
					round(new Round(Map.of("safety", 0))).failure(failure).build());
			TrialKey extension;
			try (Driver driver = harness.open())
			{
				Campaign saved = harness.runUntil(driver, state -> state.development().promptAuthor() != null &&
					state.development().promptAuthor().runs().size() == 3 &&
					state.development().promptAuthor().runs().getLast().trial() != null);
				extension = saved.development().promptAuthor().runs().getLast().trial();
				requireThat(extension.purpose(), "purpose").isEqualTo(Purpose.PROMOTION);
				requireThat(saved.development().promptAuthor().runs().stream().filter(run ->
					run.verdict() == Verdict.PASS). count(), "retainedPasses").isEqualTo(2);
			}

			try (Driver driver = harness.open())
			{
				driver.step();
				Campaign resumed = harness.campaign();
				requireThat(resumed.trials().stream().map(LogicalTrial::key).toList(), "resumedKey").contains(extension);
				requireThat(resumed.development().count("safety"), "unchangedCounts").isEqualTo(Builder.counts(60, 1));
				Campaign accepted = harness.runUntil(driver, state -> state.development().promptAuthor() != null &&
					!state.development().promptAuthor().minimality().acceptedRuns().isEmpty());
				List<PromotionRun> runs = accepted.development().promptAuthor().minimality().acceptedRuns();
				requireThat(runs.size(), "acceptedStreak").isEqualTo(3);
				requireThat(runs.getLast().trial(), "completedExtension").isEqualTo(extension);
				requireThat(runs.getLast().verdict(), "extensionVerdict").isEqualTo(Verdict.PASS);
				requireThat(accepted.attempts(), "allowance").isEmpty();
			}
		}
	}

	/**
	 * A partial fix is withdrawn after its representative interval falls below its predecessor's, restoring the
	 * predecessor's counts and worktree, ending the unproductive round and resuming the predecessor's representative
	 * trials without waiting for the user.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D15
	@Test
	public void partialFixRevertsAndResumes() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("partial-revert")),
				new Builder().confirmed().working(1).readyBaseline().ceiling(1).
					development("safety", 27, 3).round(new Round(Map.of("safety", 1))).
					failure(Builder.targeted("safety", "logs")).failure(Builder.targeted("safety", "cache")).build());
			Candidate predecessor = harness.campaign().development().working();
			Map<String, CellCounts> counts = harness.campaign().development().counts();
			AtomicInteger promptAuthors = new AtomicInteger();
			harness.promptAuthors(request -> harness.edit(request, DriverHarness.PROMPT,
				"needed-" + promptAuthors.incrementAndGet()));
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
					state -> !state.development().reverts().isEmpty());

				requireThat(reverted.development().working(), "restoredCandidate").isEqualTo(predecessor);
				requireThat(reverted.development().counts(), "restoredCounts").isEqualTo(counts);
				requireThat(harness.workspace().scanWorktree(), "restoredWorktree").
					isEqualTo(harness.overlays().require(predecessor.snapshot()).snapshot());
				requireThat(harness.promptAuthor().briefs().size(), "authorsBeforeRevert").isEqualTo(1);
				requireThat(reverted.development().round(), "round").isNull();
				requireThat(reverted.outcome(), "outcome").isNull();
				requireThat(reverted.pending(), "pending").isNull();
				requireThat(reverted.trials().stream().map(LogicalTrial::key).filter(key ->
					key.purpose() == Purpose.DEVELOPMENT && key.snapshot().equals(predecessor.snapshot())).count(),
					"resumedDevelopmentTrials").isEqualTo(1L);
			}
		}
	}


	/**
	 * A working candidate that keeps failing goes through one round after another, each ending without a promotion,
	 * until its test reaches the review checkpoint. Development stops there and offers to accept the candidate
	 * without certification.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void failingCandidateStopsAtReviewCheckpoint() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("review-checkpoint")),
				new Builder().confirmed().working(1).readyBaseline().ceiling(1).development("formatting", 100, 0).
					development("plain_list", 100, 0).
					development("safety", 195, EditOrContinue.failureLimit(Contract.DEFAULT_MINIMUM_PASS_RATE) + 1).
					sound("safety").build());
			Candidate working = harness.campaign().development().working();
			harness.trials(_ -> harness.failed());

			try (Driver driver = harness.open())
			{
				Campaign checkpoint = harness.runUntil(driver,
					state -> state.pending() instanceof PendingDecision.CheckpointReached);

				requireThat(checkpoint.pending(), "pending").
					isEqualTo(new PendingDecision.CheckpointReached("safety", Development.DEFAULT_REVIEW_POINT));
				requireThat(checkpoint.outcome(), "outcome").isNull();
				requireThat(checkpoint.development().working(), "working").isEqualTo(working);
				requireThat(checkpoint.development().round(), "round").isNull();
				requireThat(checkpoint.optimization().attempts().stream().map(AttemptResult::round).distinct().
					count(), "rounds").isGreaterThan(1L);
				requireThat(checkpoint.choices(), "choices").contains(Choice.ACCEPT_WITHOUT_CERTIFICATION);
				requireThat(driver.step(), "step").isInstanceOf(Step.Stopped.class);
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
	 * Verifies that an uncited prompt author edit contributes no candidate and the next attempt runs.
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
					failure(new Failure(Builder.targeted("safety", "cache"), "failure", List.of(), null, List.of(), List.of(),
						Builder.FILES)).
					build());
			harness.promptAuthors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				return new SessionResult.Completed(edited.transcript(), List.of(List.of("""
					{"needed_edits": [], "kinds": [{"name":"missing instruction","failures":["%s"],
					  "classification":"guidance","hunks":[]}], "hunks": [], "conflicts": []}
					""".formatted(harness.promptAuthor().briefs().getLast().developmentId(0)))), edited.effects(),
					edited.initialProject(), edited.model(), edited.reasoning(), edited.usage(), edited.windows(),
					edited.promptSources(), edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().working().number() == 2 ||
					harness.promptAuthor().briefs().size() == 2);
				requireThat(harness.campaign().candidates(), "candidates").isEqualTo(1);
				requireThat(harness.promptAuthor().briefs().size(), "prompt_authors").isEqualTo(2);
				requireThat(harness.campaign().optimization().promptAuthors().size(), "retained").isEqualTo(1);
				requireThat(harness.campaign().optimization().promptAuthors().getFirst().rejections(), "rejections").
					contains("Uncited changed hunk: CLAUDE.md#1");
				requireThat(Files.isRegularFile(harness.campaign().optimization().promptAuthors().getFirst().effects().
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
				harness.runUntil(driver, state -> state.development().promptAuthor() != null &&
					state.development().promptAuthor().minimality().testing() != null);
			}
			Campaign saved = harness.campaign();
			requireThat(saved.development().working().number(), "working").isEqualTo(1);
			requireThat(saved.development().promptAuthor().minimality().acceptedRuns(), "passingEvidence").isNotEmpty();
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
			harness.promptAuthors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				String response = """
					{"needed_edits":[],"kinds":[{"name":"missing instruction","failures":["%s"],
					 "classification":"guidance","hunks":["h1"]}],
					 "hunks":[{"id":"h1","path":"CLAUDE.md","before_start":0,"before_end":1,"after_start":0,"after_end":1,
					 "failures":["unknown"],"analogous":"related case","dissimilar":"plain_list"}],"conflicts":[]}
					""".formatted(harness.promptAuthor().briefs().getLast().developmentId(0));
				return new SessionResult.Completed(edited.transcript(), List.of(List.of(response)), edited.effects(),
					edited.initialProject(),
					edited.model(),
					edited.reasoning(), edited.usage(), edited.windows(), edited.promptSources(),
					edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> harness.promptAuthor().briefs().size() == 2);
				requireThat(harness.campaign().candidates(), "candidates").isEqualTo(1);
				requireThat(harness.campaign().optimization().promptAuthors().getFirst().rejections(), "rejections").
					isEqualTo(List.of("Unknown failure unknown cited at CLAUDE.md"));
			}
		}
	}

	/**
	 * Verifies that an attempt whose guidance kind names no hunk is rejected, is retained with its rejection,
	 * contributes no candidate, and reaches the next prompt author of the lineage as a rejected earlier manifest.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void rejectedAttemptRetainedWithoutCandidate() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new Builder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(Builder.targeted("safety", "cache")).build());
			harness.promptAuthors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				String response = """
					{"needed_edits":[],"kinds":[{"name":"skipped step","failures":["%s"],
					 "classification":"guidance","hunks":[]}],
					 "hunks":[{"id":"h1","path":"CLAUDE.md","before_start":0,"before_end":1,"after_start":0,"after_end":1,
					 "failures":["%1$s"],"analogous":"related case","dissimilar":"plain_list"}],"conflicts":[]}
					""".formatted(harness.promptAuthor().briefs().getLast().developmentId(0));
				return new SessionResult.Completed(edited.transcript(), List.of(List.of(response)), edited.effects(),
					edited.initialProject(),
					edited.model(), edited.reasoning(), edited.usage(), edited.windows(), edited.promptSources(),
					edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> harness.promptAuthor().briefs().size() == 2);
				requireThat(harness.campaign().candidates(), "candidates").isEqualTo(1);
				PromptAuthorDiagnosis retained = harness.campaign().optimization().promptAuthors().getFirst();
				requireThat(retained.rejections(), "rejections").isEqualTo(List.of(
					"Kind 'skipped step' is classified guidance but names no hunk that addresses it"));
				List<PromptAuthorBrief.EarlierManifest> earlier = harness.promptAuthor().briefs().getLast().
					earlierManifests();
				requireThat(earlier.size(), "earlier").isEqualTo(1);
				requireThat(earlier.getFirst().diagnosis(), "earlierDiagnosis").isEqualTo(retained);
				requireThat(earlier.getFirst().result(), "earlierResult").
					isEqualTo(PromptAuthorBrief.PromotionResult.REJECTED);
			}
		}
	}

	/**
	 * Verifies that an attempt that edits a file but classifies every kind not guidance contributes no candidate.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void notGuidanceEditProducesNoCandidate() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new Builder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(Builder.targeted("safety", "cache")).build());
			Sha256 working = harness.campaign().development().working().snapshot();
			harness.promptAuthors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				String response = """
					{"needed_edits":[],"kinds":[{"name":"judge outage","failures":["%s"],
					 "classification":"not_guidance","hunks":[]}],
					 "hunks":[{"id":"h1","path":"CLAUDE.md","before_start":0,"before_end":1,"after_start":0,"after_end":1,
					 "failures":["%1$s"],"analogous":"related case","dissimilar":"plain_list"}],"conflicts":[]}
					""".formatted(harness.promptAuthor().briefs().getLast().developmentId(0));
				return new SessionResult.Completed(edited.transcript(), List.of(List.of(response)), edited.effects(),
					edited.initialProject(),
					edited.model(), edited.reasoning(), edited.usage(), edited.windows(), edited.promptSources(),
					edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				Campaign ended = harness.runUntil(driver, state -> state.development().round() == null);
				requireThat(ended.candidates(), "candidates").isEqualTo(1);
				requireThat(ended.optimization().promptAuthors().getFirst().rejections(), "rejections").isEmpty();
				requireThat(ended.optimization().attempts().stream().allMatch(attempt ->
					attempt.snapshot().equals(working)), "noCandidate").isTrue();
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
			harness.promptAuthors(_ -> harness.completed("""
				{"needed_edits":[],"kinds":[{"name":"flaky harness","failures":["%s"],
				 "classification":"not_guidance","hunks":[]}],"hunks":[],"conflicts":[]}
				""".formatted(harness.promptAuthor().briefs().getLast().developmentId(0)), DriverHarness.MODEL));
			try (Driver driver = harness.open())
			{
				Campaign ended = harness.runUntil(driver, state -> state.development().round() == null);
				requireThat(ended.candidates(), "candidates").isEqualTo(1);
				requireThat(ended.optimization().promptAuthors().size(), "diagnoses").isEqualTo(2);
				requireThat(ended.optimization().promptAuthors().getFirst().kinds().getFirst().classification(),
					"classification").isEqualTo(FailureKind.NOT_GUIDANCE);
				StatusReport report = StatusReport.of(ended, ReportEvidence.NONE);
				JsonNode diagnoses = report.toJson(JsonMapper.builder().build()).path("prompt_author_diagnoses");
				requireThat(diagnoses.size(), "reportedDiagnoses").isEqualTo(2);
				requireThat(diagnoses.toString(), "diagnoses").contains("\"not_guidance\"");
				requireThat(report.summary(ZoneOffset.UTC), "summary").contains("not guidance");
			}
		}
	}

	/**
	 * Verifies that a failure that the prompt author attributes to its test requests a revision of that test and holds
	 * new trials until the request is answered.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void testDefectRequestsRevision() throws IOException
	{
		// Requirement: R-D10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("manifest")),
				new Builder().confirmed().working(1).readyBaseline().round(new Round(Map.of("safety", 0))).
					failure(Builder.targeted("safety", "cache")).build());
			harness.promptAuthors(_ -> harness.completed("""
				{"needed_edits":[],"kinds":[{"name":"named the test","failures":["%s"],
				 "classification":"test_defect","hunks":[],"fault":"The turn tells the agent that it is tested."}],
				 "hunks":[],"conflicts":[]}
				""".formatted(harness.promptAuthor().briefs().getLast().developmentId(0)), DriverHarness.MODEL));
			try (Driver driver = harness.open())
			{
				Campaign ended = harness.runUntil(driver, state -> !state.testRevisions().requests().isEmpty());
				TestRevisionRequest request = ended.testRevisions().requests().getFirst();
				requireThat(request.test(), "test").isEqualTo("safety");
				requireThat(request.trigger(), "trigger").isEqualTo(TestRevisionRequest.Trigger.TEST_DEFECT);
				requireThat(request.kinds().getFirst().fault(), "fault").
					isEqualTo("The turn tells the agent that it is tested.");
				requireThat(request.failures().size(), "failures").isEqualTo(1);
				requireThat(Scheduler.plan(ended, Instant.now(), 8), "plan").isEmpty();
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
				requireThat(harness.promptAuthor().briefs().size(), "prompt_authors").isEqualTo(2);
			}
		}
	}

	/**
	 * Creates a prompt author and trial fixture whose second hunk adds unnecessary guidance.
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
		harness.promptAuthors(request -> harness.edit(request, DriverHarness.PROMPT, "needed\nseparator\nunneeded\n"));
		harness.trials(request ->
		{
			try
			{
				if (harness.snapshot(request).equals(working) && harness.campaign().development().
					promptAuthor() != null && harness.campaign().development().promptAuthor().minimality().
					testing() == null)
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
	 * Development meeting the commit gate commits the working candidate in an attempt whose control source is the
	 * incumbent with its prompt file emptied. Development starts one trial short of the commit gate in each cell.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void commitControlsTheEmptiedIncumbent() throws IOException
	{
		// Requirement: R-E1
		// Requirement: R-F7
		Builder start = new Builder().confirmed().working(1).readyBaseline();
		for (String cell : ContractFixtures.contract().cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS - 1, 0);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start.build());
			harness.trials(_ -> harness.passed());
			try (Driver driver = harness.open())
			{
				Campaign committed = harness.runUntil(driver, state -> state.attempts().size() == 1);
				CertificationAttempt attempt = committed.attempt(1);
				requireThat(attempt.candidate(), "candidate").isEqualTo(committed.development().working());
				FileEntry controlPrompt = harness.overlays().require(attempt.control()).snapshot().
					entry(DriverHarness.PROMPT).orElseThrow();
				requireThat(controlPrompt, "controlPrompt").isEqualTo(new RegularFile(Sha256.of(new byte[0]),
					PosixFilePermissions.fromString("rw-r--r--")));
			}
		}
	}

	/**
	 * The control source that a commit saves also removes each dependent of the target in the project: a whole file
	 * is deleted, a span is deleted and a hook is unregistered, while the rest of each file stays.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void commitRemovesDependents() throws IOException
	{
		// Requirement: R-W10
		Path script = Path.of("bin/repair.sh");
		Path hooks = Path.of("hooks.json");
		Path agents = Path.of("AGENTS.md");
		String hooksText = """
			{"hooks": {"PostToolUse": [{"hooks": [{"type": "command", "command": "bin/repair.sh"}]}],
			  "SessionStart": [{"hooks": [{"type": "command", "command": "start.sh"}]}]}}
			""";
		Contract contract = ContractFixtures.withDependents(ContractFixtures.contract(), List.of(
			new Dependent(script, new Dependent.WholeFile(), "Repairs read output."),
			new Dependent(hooks,
				new Dependent.HookRegistration("PostToolUse", "bin/repair.sh"), "Runs the repair script."),
			new Dependent(agents, new Dependent.Span("Read CLAUDE.md first.\n"),
				"Routes the agent to the target.")));
		Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r--r--");
		Map<Path, String> files = Map.of(DriverHarness.PROMPT, "fork", script, "#!/bin/sh\n", hooks, hooksText,
			agents, "Read CLAUDE.md first.\nBe brief.\n");
		Map<Path, FileEntry> entries = new HashMap<>();
		for (Map.Entry<Path, String> file : files.entrySet())
			entries.put(file.getKey(), new RegularFile(Sha256.of(file.getValue().getBytes(UTF_8)), permissions));
		FileInventory fork = new FileInventory(Path.of("/project").toAbsolutePath(), entries);
		Builder start = new Builder().confirmed(contract, 1).working(1).readyBaseline();
		for (String cell : contract.cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS - 1, 0);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start.build(),
				fork);
			for (Map.Entry<Path, String> file : files.entrySet())
				harness.workspace().supplyFile(file.getKey(), new FileContent(file.getValue().getBytes(UTF_8)));
			harness.trials(_ -> harness.passed());
			try (Driver driver = harness.open())
			{
				Campaign committed = harness.runUntil(driver, state -> state.attempts().size() == 1);
				Overlay control = harness.overlays().require(committed.attempt(1).control());
				requireThat(control.snapshot().entry(script), "script").isEmpty();
				requireThat(new String(harness.workspace().readFile(control, agents).orElseThrow().bytes(), UTF_8),
					"agents").isEqualTo("Be brief.\n");
				JsonNode hookConfiguration = JsonMapper.builder().build().readTree(
					harness.workspace().readFile(control, hooks).orElseThrow().bytes()).get("hooks");
				requireThat(hookConfiguration.has("PostToolUse"), "PostToolUse").isFalse();
				requireThat(hookConfiguration.has("SessionStart"), "SessionStart").isTrue();
			}
		}
	}

	/**
	 * The end of an all-pass formal attempt: its last control trials, certification and delivery. The control trials
	 * stage the attempt's control source. Each cell starts with its minimum-pass-rate part certified and its control
	 * comparisons one tied pair short of passing, because the counts that certify are the decision boundaries' claim,
	 * not this test's.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void certify() throws IOException
	{
		// Requirement: R-E1
		Contract contract = ContractFixtures.contract();
		Builder start = new Builder().confirmed().working(1).readyBaseline();
		for (String cell : contract.cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
		DecisionBoundaries boundaries = Boundaries.standard(contract.cellIds().size(),
			Boundaries.PAIRED_MINIMUM_PASS_RATE);
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : contract.cellIds())
			oneControlPairShort(running, boundaries, contract, cell);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = runningAttemptHarness(cleanup, running.build());
			Set<Sha256> trialSnapshots = ConcurrentHashMap.newKeySet();
			harness.trials(request ->
			{
				trialSnapshots.add(harness.snapshot(request));
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.lifecycle(), "lifecycle").isEqualTo(Lifecycle.FINISHED);
				requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
				requireThat(campaign.delivery().label(), "label").isEqualTo(Outcome.CERTIFIED);
				requireThat(harness.deliverer().calls().size(), "deliveries").isEqualTo(1);
				CertificationAttempt certified = campaign.attempt(campaign.delivery().attempt());
				requireThat(trialSnapshots, "trialSnapshots").contains(certified.control());
				CertificationRecord record = harness.deliverer().calls().getFirst().record().orElseThrow();
				requireThat(record.certificationIndex(), "certificationIndex").isEqualTo(certified.index());
				// Requirement: R-W11
				requireThat(harness.deliverer().calls().getFirst().tests().stream().map(DeliveredTest::id).toList(),
					"tests").isEqualTo(campaign.contract().tests().stream().map(PromptTest::id).toList());
				requireThat(record.identity(), "identity").isEqualTo(certified.identity());
				requireThat(result.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Delivered.class);
				requireThat(result.status().status(), "status").isEqualTo(new ReportStatus.Ended(Outcome.CERTIFIED));
			}
		}
	}

	/**
	 * Certification waits for the incumbent comparisons: each cell starts with its minimum-pass-rate part certified
	 * and its control and incumbent comparisons one tied pair short of passing, and the attempt certifies only after
	 * an incumbent trial of every cell, which stages the incumbent, passes each incumbent comparison.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void certifyWaitsForIncumbentComparisons() throws IOException
	{
		// Requirement: R-E1
		// Requirement: R-F8
		Contract contract = ContractFixtures.contract();
		Builder start = new Builder().confirmed().working(1).readyBaseline();
		for (String cell : contract.cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
		DecisionBoundaries boundaries = Boundaries.standard(contract.cellIds().size(),
			Boundaries.PAIRED_MINIMUM_PASS_RATE);
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : contract.cellIds())
			oneReferencePairShort(running, boundaries, contract, cell);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = runningAttemptHarness(cleanup, running.build());
			Set<String> incumbentCells = ConcurrentHashMap.newKeySet();
			Set<Sha256> incumbentSnapshots = ConcurrentHashMap.newKeySet();
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				if (trial.key().purpose() == Purpose.INCUMBENT)
				{
					incumbentCells.add(trial.key().cell());
					incumbentSnapshots.add(harness.snapshot(request));
				}
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
				requireThat(incumbentCells, "incumbentCells").isEqualTo(new HashSet<>(contract.cellIds()));
				requireThat(incumbentSnapshots, "incumbentSnapshots").isEqualTo(Set.of(Builder.INCUMBENT));
				SignificanceLevel level = boundaries.level();
				CertificationRecord record = harness.deliverer().calls().getFirst().record().orElseThrow();
				for (CertificationRecord.CellResult cell : record.cells())
				{
					requireThat(cell.incumbentComparisons().keySet(), cell.cell() + ".incumbentComparisons").
						isEqualTo(cell.controlComparisons().keySet());
					for (Map.Entry<String, PairCounts> comparison : cell.incumbentComparisons().entrySet())
					{
						requireThat(PairedComparison.passes(comparison.getValue().tally(), level),
							cell.cell() + "." + comparison.getKey()).isTrue();
					}
				}
			}
		}
	}

	/**
	 * At the minimum pass rate 0.95 the comparisons follow from the minimum pass rate: each cell starts one passing
	 * formal trial short of certifying its minimum-pass-rate part, and the attempt certifies after those formal trials
	 * with no control or incumbent trial and no comparison in its record.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void certifyWithImpliedComparisons() throws IOException
	{
		// Requirement: R-E1
		// Requirement: R-F9
		Contract contract = ContractFixtures.contract();
		Builder start = new Builder().confirmed().working(1).readyBaseline();
		for (String cell : contract.cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
		DecisionBoundaries boundaries = Boundaries.standard(contract.cellIds().size());
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : contract.cellIds())
			running.cell(cell, certifyingPasses(boundaries) - 1, 0);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = runningAttemptHarness(cleanup, running.build());
			Set<Purpose> purposes = ConcurrentHashMap.newKeySet();
			harness.trials(request ->
			{
				purposes.add(runningTrial(harness, request).key().purpose());
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
				requireThat(purposes, "purposes").doesNotContain(Purpose.CONTROL).doesNotContain(Purpose.INCUMBENT);
				CertificationRecord record = harness.deliverer().calls().getFirst().record().orElseThrow();
				for (CertificationRecord.CellResult cell : record.cells())
				{
					requireThat(cell.controlComparisons(), cell.cell() + ".controlComparisons").isEmpty();
					requireThat(cell.incumbentComparisons(), cell.cell() + ".incumbentComparisons").isEmpty();
				}
			}
		}
	}

	/**
	 * A certified campaign whose user kept the incumbent delivers the confirmed prompt tests alone: the deliverer gets
	 * no request, the incumbent's overlay, no certification record and every prompt test, once.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void keptIncumbentDeliversPromptTests() throws IOException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("kept-incumbent")),
				new Builder().confirmed().working(1).lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).build());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Delivered.class);
			}
			requireThat(harness.deliverer().calls().size(), "deliveries").isEqualTo(1);
			RecordingDeliverer.Call call = harness.deliverer().calls().getFirst();
			requireThat(call.request(), "request").isEmpty();
			requireThat(call.selected(), "selected").isEqualTo(harness.overlays().require(Builder.INCUMBENT));
			requireThat(call.record(), "record").isEmpty();
			requireThat(call.tests().stream().map(DeliveredTest::id).toList(), "tests").
				isEqualTo(harness.campaign().contract().tests().stream().map(PromptTest::id).toList());
		}
	}

	/**
	 * A campaign that the user stopped delivers nothing, not even its prompt tests.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void stoppedCampaignDeliversNothing() throws IOException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("stopped")),
				new Builder().confirmed().working(1).lifecycle(Lifecycle.FINISHED).outcome(Outcome.INCONCLUSIVE).build());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.delivery(), "delivery").isNull();
			}
			requireThat(harness.deliverer().calls(), "deliveries").isEmpty();
		}
	}

	/**
	 * Sets a cell of a running attempt to the fewest passes that certify its minimum-pass-rate part, or to its paired
	 * passes if more, with each of its control comparisons one tied pair short of passing. The cell certifies once one
	 * more pair of passing trials is adopted.
	 *
	 * @param attempt the attempt
	 * @param boundaries the attempt's boundaries
	 * @param contract the attempt's contract
	 * @param cell the cell identifier
	 */
	private static void oneControlPairShort(Builder.AttemptBuilder attempt, DecisionBoundaries boundaries,
		Contract contract, String cell)
	{
		Pairing pairing = onePairShort(boundaries, contract, cell);
		attempt.cell(cell, Math.max(certifyingPasses(boundaries), pairing.pairs()), 0).controlPairing(cell, pairing);
	}

	/**
	 * Sets a cell of a running attempt as {@link #oneControlPairShort} does, with each of its incumbent comparisons
	 * also one tied pair short of passing. The cell certifies once one more pair of passing trials of each kind is
	 * adopted.
	 *
	 * @param attempt the attempt
	 * @param boundaries the attempt's boundaries
	 * @param contract the attempt's contract
	 * @param cell the cell identifier
	 */
	private static void oneReferencePairShort(Builder.AttemptBuilder attempt, DecisionBoundaries boundaries,
		Contract contract, String cell)
	{
		oneControlPairShort(attempt, boundaries, contract, cell);
		attempt.incumbentPairing(cell, onePairShort(boundaries, contract, cell));
	}

	/**
	 * Returns comparisons of a cell in which each assertion is one tied pair short of passing.
	 *
	 * @param boundaries the attempt's boundaries
	 * @param contract the attempt's contract
	 * @param cell the cell identifier
	 * @return the comparisons, with no unpaired formal trial
	 */
	private static Pairing onePairShort(DecisionBoundaries boundaries, Contract contract, String cell)
	{
		int pairs = PairedComparison.fewestEvenPairsToPass(PairTally.EMPTY, boundaries.level(),
			Boundaries.STANDARD_CAP) - 1;
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		for (Assertion assertion : contract.testOf(contract.findCell(cell).orElseThrow()).allAssertions())
			comparisons.put(assertion.id(), new PairCounts(pairs, 0, 0, 0));
		return new Pairing(List.of(), comparisons);
	}

	/**
	 * Returns the fewest passes, without failures, that certify a cell's minimum-pass-rate part.
	 *
	 * @param boundaries the attempt's boundaries
	 * @return the number of passes
	 */
	private static int certifyingPasses(DecisionBoundaries boundaries)
	{
		int passes = 1;
		while (!boundaries.certifies(passes, 0))
			++passes;
		return passes;
	}

	/**
	 * Returns ports for a campaign that starts with a running attempt, whose control source the campaign builder
	 * names without content: the control trials stage the incumbent with its prompt file emptied.
	 *
	 * @param cleanup the temporary directories of the test
	 * @param campaign the campaign
	 * @return the ports
	 * @throws IOException if the test directory cannot be used
	 */
	private static DriverHarness runningAttemptHarness(TemporaryDirectories cleanup, Campaign campaign)
		throws IOException
	{
		DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), campaign);
		harness.overlays().put(Builder.CONTROL, harness.candidate(""));
		return harness;
	}

	/**
	 * Returns boundaries that certify a test at {@code 100 + 50·F} passes, find it clearly below at 5 failures,
	 * and never prove it better or worse than the incumbent.
	 *
	 * @return the boundaries
	 */
	private static DecisionBoundaries neutralBoundaries()
	{
		ComparisonBoundaries neutral = Boundaries.comparison(Boundaries.STANDARD_CAP, 1000, 0, Boundaries.STANDARD_CAP + 1);
		return Boundaries.decision(Boundaries.STANDARD_CAP, 100, 50, 5, List.of(neutral, neutral, neutral));
	}

	/**
	 * A development failure, a rerun that reproduces it, a prompt author's edit, promotion runs, and a new working
	 * candidate whose counts start at zero with the concurrency window back to 1. The prompt author receives the
	 * judge's rationales for the failure and for its rerun. The optimization worktree holds the new working candidate.
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
					development("plain_list", 50, 0).development("safety", 50, 0).build());
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
				PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
				requireThat(brief.cell(), "test").isEqualTo("safety");
				// The prompt author receives the judge's rationale for the failure and for the rerun that reproduced it
				Failure failure = brief.developmentFailures().getFirst();
				String rationale = deletionFailureRationale("The trial did not meet the requirements.");
				requireThat(failure.rationale(), "rationale").isEqualTo(rationale);
				requireThat(failure.reruns(), "reruns").
					isEqualTo(List.of(new DiagnosticRerun(Verdict.FAIL, rationale)));
				// Only the development failure is triaged; its diagnostic rerun is not
				requireThat(failure.triage().impact(), "impact").isEqualTo(Impact.SELF_RECOVERING);
				requireThat(triageRequests(harness).size(), "triages").isEqualTo(1);
				// The prompt author session's project source is the first authoring copy that the workspace prepared
				SessionRequest promptAuthor = harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.PROMPT_AUTHOR).findFirst().orElseThrow();
				requireThat(promptAuthor.source(), "source").
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
					development("plain_list", 50, 0).development("safety", 50, 0).build());
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
			// The safety test has 4 outcomes left before its cap, so exactly 4 trials are admitted
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					slowStart(new SlowStart(4, 0)).certificationAttempt(1).boundaries(neutralBoundaries()).
					cell("formatting", 100, 0).cell("plain_list", 100, 0).cell("safety", 292, 4).build());
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
			development("plain_list", 50, 0).development("safety", 50, 0);
	}

	/**
	 * Returns the number of outcomes that a campaign's development adopted.
	 *
	 * @param campaign the campaign
	 * @return the outcomes of every test
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
			requireThat(developmentOutcomes(crashed), "outcomes").isEqualTo(150);
			requireThat(trials(crashed, TrialState.RUNNING).size(), "running").isEqualTo(1);

			harness.crashingStore().restart();
			try (Driver _ = harness.open())
			{
				Campaign reconciled = harness.campaign();
				requireThat(developmentOutcomes(reconciled), "outcomes").isEqualTo(151);
				requireThat(reconciled.evidenceRevision(), "evidenceRevision").
					isEqualTo(crashed.evidenceRevision() + 1);
				requireThat(reconciled.trials(), "trials").isEmpty();
			}
			try (Driver _ = harness.open())
			{
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(151);
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
					cell("plain_list", 100, 0).cell("safety", 100, 0).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "trials").isEqualTo(1);
				requireThat(DriverHarness.cell(trials.getFirst().request()), "test").isEqualTo("formatting");
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
				started.getFirst().end(new SessionResult.RateLimited(new Hold(resetsAt, 0), List.of()));
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
					passed.effects(), passed.initialProject(), passed.model(), passed.reasoning(), usage, windows,
					passed.promptSources(),
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
				started.getFirst().end(new SessionResult.RateLimited(new Hold(resetsAt, 0), windows));
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
					return new SessionResult.RateLimited(new Hold(resetsAt.get(), 0), List.of());
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
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(151);
			}
		}
	}

	/**
	 * A provider refusal of a trial session holds admissions until the harness's shared hold ends and then reruns the
	 * same logical trial. A refusal is not an infrastructure failure, so three refusals in a row do not pause the
	 * campaign.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void refusedTrialRerunsAfterHold() throws IOException
	{
		// Requirement: R-E2
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("refused-trial")),
				developing().slowStart(new SlowStart(1, 0)).build());
			// The slice outlasts the holds, so that each rerun fits before its deadline
			harness.settings(new SliceSettings(Duration.ofHours(1), Duration.ofMinutes(1), Duration.ofSeconds(1)));
			try (Driver driver = harness.open())
			{
				driver.step();
				SessionRequest refusedRequest = harness.runningTrials().getFirst().request();
				for (int refusal = 1; refusal <= 3; ++refusal)
				{
					Instant end = harness.clock().instant().plus(Duration.ofMinutes(1L << (refusal - 1)));
					harness.runningTrials().getFirst().end(
						new SessionResult.Refused(new Hold(end, refusal), false, List.of()));
					driver.step();
					Campaign campaign = harness.campaign();
					requireThat(campaign.throttle().holdUntil(), "holdUntil").isEqualTo(end);
					requireThat(campaign.pending(), "pending").isNull();
					List<LogicalTrial> queued = trials(campaign, TrialState.ASSIGNED);
					requireThat(queued.size(), "queued").isEqualTo(1);
					requireThat(queued.getFirst().infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
					requireThat(harness.runningTrials(), "runningBeforeEnd").isEmpty();

					harness.clock().set(end);
					driver.step();
					List<FakeSession> rerun = harness.runningTrials();
					requireThat(rerun.size(), "rerun").isEqualTo(1);
					requireThat(rerun.getFirst().request(), "rerunRequest").isEqualTo(refusedRequest);
				}
			}
		}
	}

	/**
	 * A provider refusal that follows a 30-minute hold stops the slice with a message that names the harness, and
	 * the refused trial waits in the queue without counting as an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void refusalAfterLongestHoldPauses() throws IOException
	{
		// Requirement: R-E8
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("refused-longest")),
				developing().slowStart(new SlowStart(1, 0)).build());
			harness.trials(_ -> new SessionResult.Refused(
				new Hold(harness.clock().instant().plus(Duration.ofMinutes(30)), 6), true, List.of()));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").
					isEqualTo("The provider refused claude requests after a 30-minute hold");
				Campaign campaign = harness.campaign();
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(1);
				List<LogicalTrial> queued = trials(campaign, TrialState.ASSIGNED);
				requireThat(queued.size(), "queued").isEqualTo(1);
				requireThat(queued.getFirst().infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that a provider refusal of a judge session holds admissions until the harness's shared hold ends and
	 * then reruns the judge with the same evidence, without counting the refusal as a failure of the judge or of the
	 * trial.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void refusedJudgeReruns() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("refused-judge")),
				developing().slowStart(new SlowStart(1, 0)).build());
			AtomicInteger refusals = new AtomicInteger(Driver.JUDGE_ATTEMPTS);
			AtomicReference<Instant> holdEnd = new AtomicReference<>();
			harness.judges(request ->
			{
				if (refusals.getAndDecrement() > 0)
					return new SessionResult.Refused(new Hold(holdEnd.get(), 1), false, List.of());
				return harness.scriptedJudgment(request);
			});
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.endSessions(), "trials").isEqualTo(1);
				for (int refusal = 1; refusal <= Driver.JUDGE_ATTEMPTS; ++refusal)
				{
					Instant end = harness.clock().instant().plus(Duration.ofMinutes(1));
					holdEnd.set(end);
					harness.settle(driver);
					requireThat(judgeRequests(harness).size(), "judges").isEqualTo(refusal);
					requireThat(harness.campaign().throttle().holdUntil(), "holdUntil").isEqualTo(end);
					harness.clock().set(end);
				}
				harness.settle(driver);
				List<SessionRequest> judges = judgeRequests(harness);
				requireThat(judges.size(), "judges").isEqualTo(Driver.JUDGE_ATTEMPTS + 1);
				for (SessionRequest judge : judges)
					requireThat(judge, "judge").isEqualTo(judges.getFirst());
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(151);
			}
		}
	}

	/**
	 * Verifies that a provider refusal of a prompt author session reruns the prompt author with the same brief once the
	 * harness's shared hold ends, and that refusals do not count toward the prompt author failures that stop the slice.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void refusedPromptAuthorReruns() throws IOException
	{
		// Requirement: R-E2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("refused-prompt-author")),
				authoring().build());
			// The harness ends each prompt author session at the clock time of the step that started it
			List<Instant> starts = new ArrayList<>();
			List<Instant> ends = new ArrayList<>();
			harness.promptAuthors(request ->
			{
				Instant now = harness.clock().instant();
				starts.add(now);
				if (starts.size() > Driver.PROMPT_AUTHOR_FAILURE_LIMIT)
					return harness.edit(request, DriverHarness.PROMPT, "edited\n");
				Instant end = now.plus(Duration.ofMinutes(1));
				ends.add(end);
				return new SessionResult.Refused(new Hold(end, starts.size()), false, List.of());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> starts.size() > Driver.PROMPT_AUTHOR_FAILURE_LIMIT);
				List<PromptAuthorBrief> briefs = harness.promptAuthor().briefs();
				requireThat(briefs.size(), "briefs").isEqualTo(Driver.PROMPT_AUTHOR_FAILURE_LIMIT + 1);
				for (PromptAuthorBrief brief : briefs)
					requireThat(brief, "brief").isEqualTo(briefs.getFirst());
				for (int refusal = 0; refusal < ends.size(); ++refusal)
				{
					requireThat(starts.get(refusal + 1), "rerunStart").
						isGreaterThanOrEqualTo(ends.get(refusal));
				}
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
			harness.workspace().changeWorktree(new FileInventory(Path.of("/project").toAbsolutePath(),
				Map.of(DriverHarness.PROMPT, new FileEntry.RegularFile(Sha256.of("user edit".getBytes(UTF_8)),
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
	public void openKeepsFailureWhenUnlockFails() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("driver-unlock"));
			DriverHarness harness = new DriverHarness(directory, developing().build());
			IOException release = new IOException("release failed");
			try (FaultingFileChannel channel = new FaultingFileChannel(null, release, null))
			{
				try
				{
					// Driver.open takes ownership of the lock and releases it when it fails
					Driver.open(harness.context(), "missing-campaign",
						CampaignLock.tryAcquire(directory.resolve("unlock.lock"), _ -> channel).orElseThrow()).close();
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
	 * A certification attempt reaches its cap with one test proven better and the other certified.
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
	 * also qualifies with a lower bound on its weakest test, and later attempts fail. At the stop, the campaign
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
					certificationAttempt(1).candidate(first).cell("formatting", 296, 4).cell("plain_list", 250, 0).
					cell("safety", 250, 0).outcome(Outcome.INCONCLUSIVE).campaign().
					certificationAttempt(2).cell("formatting", 295, 4).cell("plain_list", 100, 0).
					cell("safety", 100, 0).build());
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
				requireThat(call.request().orElseThrow().candidate(), "candidate").isEqualTo(first);
				requireThat(call.selected(), "selected").isEqualTo(harness.overlays().require(first.snapshot()));
				requireThat(call.working(), "working").
					isEqualTo(harness.overlays().require(Builder.snapshot("candidate-2")));
				requireThat(call.record(), "record").isEmpty();
				// Requirement: R-W11
				requireThat(call.tests(), "tests").isEmpty();
			}
		}
	}

	/**
	 * Mid-attempt, a run reports a different model. Its record holds no outcome and the campaign pauses.
	 * Restoring reruns the same test case and the attempt continues. A second change followed by a new measurement
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
					certificationAttempt(1).candidate(first).cell("formatting", 296, 4).cell("plain_list", 250, 0).
					cell("safety", 250, 0).outcome(Outcome.INCONCLUSIVE).campaign().
					certificationAttempt(2).cell("formatting", 50, 0).cell("plain_list", 50, 0).
					cell("safety", 50, 0).build());
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
					changed.attempt(2).count("plain_list").outcomes() + changed.attempt(2).count("safety").outcomes(),
					"outcomes").isEqualTo(150);
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
					restored.attempt(2).count("plain_list").outcomes() + restored.attempt(2).count("safety").outcomes(),
					"outcomes").isEqualTo(151);

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
	 * A test whose file is unbound runs under two profiles, and each of its cells has its own commit gate: the candidate
	 * is committed once the small cell, which starts one development trial short of the commit gate, meets it, and only
	 * that cell's trials run.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void commitWaitsForEachProfileCell() throws IOException
	{
		// Requirement: R-C11
		// Requirement: R-E17
		Campaign start = twoProfileStart().development(ContractFixtures.SMALL_CELL,
			CommitGate.MINIMUM_TRIALS - 1, 0).build();
		requireThat(CommitGate.isCommitDue(start), "commitDueBeforeSmallCell").isFalse();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start);
			harness.trials(request -> harness.completed("pass", request.profile().model()));
			try (Driver driver = harness.open())
			{
				Campaign committed = harness.runUntil(driver, state -> state.attempts().size() == 1);
				requireThat(committed.development().count(ContractFixtures.SMALL_CELL).passes(), "smallDevelopment").
					isGreaterThanOrEqualTo(CommitGate.MINIMUM_TRIALS);
				requireThat(committed.development().count(ContractFixtures.BIG_CELL).passes(), "bigDevelopment").
					isEqualTo(CommitGate.MINIMUM_TRIALS);
				requireThat(committed.attempt(1).procedure().cells(), "committedCells").
					isEqualTo(List.of(ContractFixtures.BIG_CELL, ContractFixtures.SMALL_CELL, ContractFixtures.PLAIN_BIG_CELL,
						ContractFixtures.PLAIN_SMALL_CELL, "safety"));
			}
			Set<String> trialCells = new HashSet<>();
			for (FakeSession session : harness.sessions().sessions())
			{
				SessionRequest request = session.request();
				if (request.role() == SessionRole.TRIAL)
				{
					trialCells.add(DriverHarness.cell(request));
					requireThat(request.profile(), "trialProfile").isEqualTo(ContractFixtures.SMALL);
				}
			}
			requireThat(trialCells, "trialCells").isEqualTo(Set.of("formatting"));
		}
	}

	/**
	 * In a formal attempt, a trial runs under its cell's profile while the judge runs under the role profile; each
	 * cell has its own decision and records the model its trials observed. The weaker cells start one control pair
	 * short of certifying, and the stronger cells, which open once they certify, fail under the big profile.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void cellsRunUnderTheirProfiles() throws IOException
	{
		// Requirement: R-C11
		// Requirement: R-E17
		Builder start = twoProfileStart();
		Contract contract = ContractFixtures.twoCellContract();
		DecisionBoundaries boundaries = Boundaries.standard(contract.cellIds().size(),
			Boundaries.PAIRED_MINIMUM_PASS_RATE);
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : List.of(ContractFixtures.SMALL_CELL, ContractFixtures.PLAIN_SMALL_CELL, "safety"))
			oneControlPairShort(running, boundaries, contract, cell);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = runningAttemptHarness(cleanup, running.build());
			harness.trials(request ->
			{
				// The weaker cells certify first, which opens the stronger cells, whose formatting trials fail
				if (DriverHarness.cell(request).equals("formatting") && request.profile().equals(ContractFixtures.BIG))
					return harness.failed(request.profile().model());
				return harness.completed("pass", request.profile().model());
			});
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, state -> !state.attempt(1).isRunning());
			}

			CertificationAttempt attempt = campaign.attempt(1);
			requireThat(attempt.count(ContractFixtures.SMALL_CELL).failures(), "smallFailures").isEqualTo(0);
			requireThat(attempt.count(ContractFixtures.BIG_CELL).passes(), "bigPasses").isEqualTo(0);
			requireThat(DecisionTable.minimumDecision(boundaries, attempt.count(ContractFixtures.BIG_CELL)),
				"bigDecision").isEqualTo(CellDecision.CLEARLY_BELOW);
			requireThat(DecisionTable.minimumDecision(boundaries, attempt.count(ContractFixtures.SMALL_CELL)),
				"smallDecision").isEqualTo(CellDecision.CERTIFIED);
			requireThat(attempt.outcome(), "outcome").isEqualTo(Outcome.BELOW_MINIMUM);

			// The formatting and plain_list tests bind no profile, so their trials run under both profiles
			Map<TrialProfile, Integer> unbound = new HashMap<>();
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
						unbound.merge(request.profile(), 1, Integer::sum);
				}
			}
			requireThat(judges, "judges").isPositive();
			requireThat(unbound.keySet(), "unboundProfiles").
				isEqualTo(Set.of(ContractFixtures.BIG, ContractFixtures.SMALL));

			MeasurementIdentity identity = campaign.identity();
			requireThat(identity.cells().get(ContractFixtures.BIG_CELL).get(MeasurementCondition.MODEL), "bigModel").
				isEqualTo("big");
			requireThat(identity.cells().get(ContractFixtures.SMALL_CELL).get(MeasurementCondition.MODEL),
				"smallModel").isEqualTo("small");
		}
	}

	/**
	 * Returns a campaign of the two-profile contract whose identity leaves the model to each cell, with every cell
	 * meeting the commit gate.
	 *
	 * @return the campaign builder
	 */
	private static Builder twoProfileStart()
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.OBSERVED.values());
		values.remove(MeasurementCondition.MODEL);
		Builder start = new Builder().identity(new MeasurementIdentity(values)).
			confirmed(ContractFixtures.twoCellContract(), 1).working(1).readyBaseline();
		for (String cell : ContractFixtures.twoCellContract().cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
		return start;
	}

	/**
	 * Each trial and each prompt measurement stages only the files of its candidate that load the prompt system into a
	 * trial, so that a trial's project holds none of the project's other files.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void trialsStageBaseSelection() throws IOException
	{
		// Requirement: R-W15
		TrialPlugin plugin = builtPlugin();
		Contract contract = ContractFixtures.contract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("selected-trials")),
				new Builder().confirmed(contract, 1).working(1).plugins(List.of(plugin)).build());
			List<SessionRequest> trials = new CopyOnWriteArrayList<>();
			harness.trials(request ->
			{
				trials.add(request);
				return harness.passed();
			});
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, _ -> trials.size() >= 2);
			}
			SourceSelection expected = TrialSelection.base(campaign.header().harness(), Set.of(DriverHarness.PROMPT),
				Set.of(), campaign.contract(), List.of(plugin));
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
			{
				Sha256 candidate = DriverHarness.stagedCandidate(trial);
				requireThat(Set.copyOf(harness.workspace().selections(candidate)), "selections").
					isEqualTo(Set.of(expected));
				requireThat(harness.workspace().stagedWhole(candidate), "stagedWhole").isFalse();
			}
		}
	}

	/**
	 * Each trial installs the copy of every trial plugin that belongs to its staged source, so that the trial loads
	 * its candidate's prompt files through the plugins.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void trialsInstallTrialPlugins() throws IOException
	{
		// Requirement: R-W12
		TrialPlugin plugin = builtPlugin();
		TrialPlugin installed = new TrialPlugin("tools@market", "b".repeat(64), new TrialPlugin.Installed(),
			List.of());
		Contract contract = ContractFixtures.contract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("plugin-trials")),
				new Builder().confirmed(contract, 1).working(1).plugins(List.of(plugin, installed)).build());
			List<SessionRequest> trials = new CopyOnWriteArrayList<>();
			harness.trials(request ->
			{
				trials.add(request);
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> trials.size() >= 2);
			}
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
			{
				List<Path> expected = List.of(harness.workspace().pluginCopy(plugin, trial.source(), PluginRemovals.NONE),
					harness.workspace().pluginCopy(installed, trial.source(), PluginRemovals.NONE));
				requireThat(trial.plugins(), "plugins").isEqualTo(expected);
			}
		}
	}

	/**
	 * Control trials install a copy of a project-built trial plugin without the target's dependents in the plugin, and
	 * every other trial installs the copy with them. Each cell starts one control pair short of passing.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void controlTrialsRemovePluginDependents() throws IOException
	{
		// Requirement: R-W10
		TrialPlugin plugin = builtPlugin();
		Contract contract = ContractFixtures.withDependents(ContractFixtures.contract(),
			List.of(new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.WholeFile(),
				"Reads the part that the target tells the agent to read.")));
		Builder start = new Builder().confirmed(contract, 1).working(1).plugins(List.of(plugin)).readyBaseline();
		for (String cell : contract.cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
		DecisionBoundaries boundaries = Boundaries.standard(contract.cellIds().size(),
			Boundaries.PAIRED_MINIMUM_PASS_RATE);
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : contract.cellIds())
			oneControlPairShort(running, boundaries, contract, cell);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = runningAttemptHarness(cleanup, running.build());
			List<SessionRequest> trials = new CopyOnWriteArrayList<>();
			harness.trials(request ->
			{
				trials.add(request);
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runSlice(driver);
			}
			Sha256 control = harness.campaign().attempt(1).control();
			PluginRemovals removals = PluginRemovals.of(contract.pluginRemovals(plugin), JsonMapper.builder().build());
			int controlTrials = 0;
			for (SessionRequest trial : trials)
			{
				PluginRemovals expected = PluginRemovals.NONE;
				if (harness.snapshot(trial).equals(control))
				{
					expected = removals;
					++controlTrials;
				}
				requireThat(trial.plugins(), "plugins").isEqualTo(List.of(
					harness.workspace().pluginCopy(plugin, trial.source(), expected)));
			}
			requireThat(controlTrials, "controlTrials").isPositive();
		}
	}

	/**
	 * Returns a trial plugin that the project builds.
	 *
	 * @return the plugin
	 */
	private static TrialPlugin builtPlugin()
	{
		return new TrialPlugin("cat", "a".repeat(64), new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(new TrialPlugin.Mapping(Path.of("rules"), Path.of("rules/codex"))));
	}

	/**
	 * Under the latest-versions scope, development samples only each test's weakest cell, and the candidate is
	 * committed once that cell meets the commit gate. The weakest cells start one development trial short of it.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void developmentSamplesWeakestCells() throws IOException
	{
		// Requirement: R-E19
		Contract contract = TierFixtures.tieredContract();
		Campaign start = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(TierFixtures.SMALL_HIGH, CommitGate.MINIMUM_TRIALS - 1, 0).
			development(TierFixtures.SAFETY, CommitGate.MINIMUM_TRIALS - 1, 0).build();
		requireThat(CellTier.scopeCellIds(start.contract()).size(), "scopeCells").isEqualTo(6);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start);
			harness.trials(_ -> harness.passed());
			try (Driver driver = harness.open())
			{
				Campaign committed = harness.runUntil(driver, state -> state.attempts().size() == 1);
				requireThat(committed.development().counts().keySet(), "developmentCells").
					isEqualTo(Set.of(TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
				requireThat(CommitGate.isMetInCell(committed.development().count(TierFixtures.SMALL_HIGH),
					Contract.DEFAULT_MINIMUM_PASS_RATE), "weakestReady").isTrue();
			}
		}
	}

	/**
	 * A formal failure in a scope cell that development does not sample makes that cell a development cell once
	 * reproduction trials reproduce it there. The attempt starts with the cells weaker than the failing cell
	 * certified.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void developmentCellsGrowOnFormalFailure() throws IOException
	{
		// Requirement: R-E19
		// Requirement: R-E3
		Contract contract = TierFixtures.tieredContract();
		Builder start = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(TierFixtures.SMALL_HIGH, CommitGate.MINIMUM_TRIALS, 0).
			development(TierFixtures.SAFETY, CommitGate.MINIMUM_TRIALS, 0);
		DecisionBoundaries boundaries = Boundaries.standard(CellTier.scopeCellIds(start.build().contract()).size());
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : List.of(TierFixtures.BIG_LOW, TierFixtures.SMALL_HIGH, TierFixtures.SAFETY))
			running.cell(cell, certifyingPasses(boundaries), 0);
		TrialProfile medium = new TrialProfile("big", "medium");
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = runningAttemptHarness(cleanup, running.build());
			harness.trials(request ->
			{
				try
				{
					boolean formal = harness.campaign().runningAttempt().isPresent();
					boolean reproduction = runningTrial(harness, request).key().purpose() == Purpose.REPRODUCTION;
					if ((formal || reproduction) && request.profile().equals(medium))
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
				Campaign failed = harness.runUntil(driver, state -> !state.attempt(1).isRunning());
				requireThat(failed.attempt(1).count(TierFixtures.BIG_MEDIUM).failures(), "formalFailures").
					isPositive();
				requireThat(CellTier.developmentCellIds(failed), "developmentCells").
					isEqualTo(List.of(TierFixtures.BIG_MEDIUM, TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
				Campaign grown = harness.runUntil(driver,
					state -> state.development().counts().containsKey(TierFixtures.BIG_MEDIUM));
				requireThat(grown.attempts().size(), "attempts").isEqualTo(1);
			}
		}
	}

	/**
	 * After a formal failure in a scope cell that development does not otherwise sample, the next commit waits until that
	 * cell meets the commit gate too, and the stronger cells stay out of development. The failed cell starts one
	 * development trial short of the commit gate.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void commitWaitsForTheFailedCell() throws IOException
	{
		// Requirement: R-E19
		// Requirement: R-E3
		Contract contract = TierFixtures.tieredContract();
		DrawnTestCase failure = new Generator(contract).draw(TierFixtures.BIG_MEDIUM,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		Campaign start = new Builder().confirmed(contract, 1).working(1).readyBaseline().certificationAttempt(1).
			cell(TierFixtures.BIG_MEDIUM, 0, 1).failure(failure).outcome(Outcome.BELOW_MINIMUM).campaign().
			working(2).development(TierFixtures.SMALL_HIGH, CommitGate.MINIMUM_TRIALS, 0).
			development(TierFixtures.SAFETY, CommitGate.MINIMUM_TRIALS, 0).
			development(TierFixtures.BIG_MEDIUM, CommitGate.MINIMUM_TRIALS - 1, 0).build();
		requireThat(CellTier.developmentCellIds(start), "developmentCells").
			isEqualTo(List.of(TierFixtures.BIG_MEDIUM, TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
		requireThat(CommitGate.isCommitDue(start), "commitDueBeforeFailedCell").isFalse();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), start);
			harness.trials(_ -> harness.passed());
			try (Driver driver = harness.open())
			{
				Campaign recommitted = harness.runUntil(driver, state -> state.attempts().size() == 2);
				Development development = recommitted.development();
				requireThat(CommitGate.isMetInCell(development.count(TierFixtures.BIG_MEDIUM),
					Contract.DEFAULT_MINIMUM_PASS_RATE), "failedCellReady").isTrue();
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
		DrawnTestCase failed = new Generator(contract).draw(TierFixtures.BIG_LOW,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		PromptTest test = contract.findTest(failed.testId()).orElseThrow();
		List<TriedTestCase> tried = new ArrayList<>();
		List<AddedTestCase> added = ContractFixtures.addedTestCases(test, 1, "a");
		for (int i = 0; i < added.size(); ++i)
		{
			int trials = 25;
			if (i == added.size() - 1)
				trials = 24;
			tried.add(new TriedTestCase(added.get(i), trials, 0));
		}
		FailedAssertion wrongTone = new FailedAssertion(test.assertions().getFirst().assertion().id(), "wrong_tone");
		Failure failure = new Failure(failed, "", List.of(wrongTone), null, List.of(), List.of(), Builder.FILES);
		FailureGroup group = new FailureGroup(TierFixtures.BIG_LOW, test.id(), wrongTone, 2, FailureGroup.ROUNDS,
			List.of(new ReproductionRound(TierFixtures.BIG_LOW, tried)), FailureGroup.Status.REPRODUCING);
		Campaign start = new Builder().header(new Header(DriverHarness.CAMPAIGN, Path.of("/project").toAbsolutePath(),
			Path.of("/project").toAbsolutePath().resolve(DriverHarness.PROMPT), Harness.CODEX, Lifecycle.ACTIVE)).
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
	 * A certification attempt whose formal trials fail in the weakest cell of a test ends there, and no formal
	 * trial runs in the test's stronger cells, which never open.
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
	 * Development, diagnostic, promotion and reproduction trials run development test cases, or values that development
	 * added, and request sessions of the development part; baseline, formal, control and incumbent trials run held-out
	 * ones and request sessions of the held-out part. After a failed attempt, the prompt author brief names each formal
	 * failure's cell, test and failed requirements, and the prompt author's prompt holds no held-out test case, reply
	 * or judge rationale. The baseline starts one trial short of its target, and the formal attempt starts with the
	 * cells weaker than the failing cell certified except for one, which is one control pair and one incumbent pair
	 * short, so that a control trial and an incumbent trial run before the failing cell opens.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void heldOutTestCasesStayOutOfDevelopment() throws IOException
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
		Builder start = new Builder().confirmed(contract, 1).working(1).
			development(TierFixtures.SMALL_HIGH, CommitGate.MINIMUM_TRIALS, 0).
			development(TierFixtures.SAFETY, CommitGate.MINIMUM_TRIALS, 0);
		for (String cell : contract.cellIds())
			start.baseline(cell, Baseline.TARGET - 1, 0);
		Contract scoped = start.build().contract();
		DecisionBoundaries boundaries = Boundaries.standard(CellTier.scopeCellIds(scoped).size(),
			Boundaries.PAIRED_MINIMUM_PASS_RATE);
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : List.of(TierFixtures.SMALL_HIGH, TierFixtures.SAFETY))
			running.cell(cell, certifyingPasses(boundaries), 0);
		oneReferencePairShort(running, boundaries, scoped, TierFixtures.BIG_LOW);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = runningAttemptHarness(cleanup, running.build());
			Map<Purpose, List<DrawnTestCase>> testCasesByPurpose = new EnumMap<>(Purpose.class);
			Map<Purpose, Set<Part>> requestPartsByPurpose = new EnumMap<>(Purpose.class);
			AtomicBoolean developmentFailed = new AtomicBoolean();
			Campaign campaign;
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				Purpose purpose = trial.key().purpose();
				testCasesByPurpose.computeIfAbsent(purpose, _ -> new ArrayList<>()).add(trial.testCase());
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
			requireThat(testCasesByPurpose.keySet(), "purposes").isEqualTo(Set.of(Purpose.values()));
			for (Map.Entry<Purpose, List<DrawnTestCase>> entry : testCasesByPurpose.entrySet())
			{
				Part expected = switch (entry.getKey())
				{
					case DEVELOPMENT, DIAGNOSTIC, PROMOTION, REPRODUCTION -> Part.DEVELOPMENT;
					case BASELINE, FORMAL, CONTROL, INCUMBENT -> Part.HELD_OUT;
				};
				for (DrawnTestCase testCase : entry.getValue())
					requireThat(partOf(campaign, testCase), entry.getKey().name()).isEqualTo(expected);
				requireThat(requestPartsByPurpose.get(entry.getKey()), entry.getKey().name() + ".requestParts").
					isEqualTo(Set.of(expected));
			}

			PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
			requireThat(brief.cell(), "cell").isEqualTo(TierFixtures.BIG_MEDIUM);
			List<SessionRequest> promptAuthorRequests = harness.sessions().sessions().stream().
				map(FakeSession::request). filter(request -> request.role() == SessionRole.PROMPT_AUTHOR).toList();
			String turn = promptAuthorRequests.getFirst().turns().getFirst();
			requireThat(turn, "turn").doesNotContain(heldOutReply).doesNotContain(heldOutRationale);
			// A held-out test case can share a turn with a development test case, such as a turn that uses only some of
			// the variables; only its expected outcome and the turns that no development trial ran are held-out content
			Set<String> developmentTurns = new HashSet<>();
			for (Purpose purpose : List.of(Purpose.DEVELOPMENT, Purpose.DIAGNOSTIC, Purpose.PROMOTION,
				Purpose.REPRODUCTION))
			{
				for (DrawnTestCase testCase : testCasesByPurpose.get(purpose))
					developmentTurns.addAll(testCase.turns());
			}
			for (DrawnTestCase formal : testCasesByPurpose.get(Purpose.FORMAL))
			{
				requireThat(turn, "turn").doesNotContain(formal.testCase().expectedOutcome());
				for (String formalTurn : formal.turns())
				{
					if (!developmentTurns.contains(formalTurn))
						requireThat(turn, "turn").doesNotContain(formalTurn);
				}
			}
			String endedSection = turn.substring(turn.indexOf("certification attempts that have ended"),
				turn.indexOf("\n\nThe manifests of the earlier"));
			JsonNode ended = JsonMapper.builder().build().readTree(endedSection.substring(endedSection.indexOf('[')));
			requireThat(ended.size(), "ended.size()").isEqualTo(1);
			JsonNode failures = ended.get(0).get("failures");
			requireThat(failures.size(), "failures.size()").isPositive();
			for (JsonNode failure : failures)
			{
				requireThat(new HashSet<>(failure.propertyNames()), "fields").
					isEqualTo(Set.of("cell", "test", "failed_assertions"));
				requireThat(failure.get("cell").asString(), "cell").isEqualTo(TierFixtures.BIG_MEDIUM);
				// Requirement: R-C18
				JudgedAssertion judged = JudgedAssertion.of(contract.findTest(failure.get("test").asString()).
					orElseThrow(), contract.requirements(), List.of()).getFirst();
				JsonNode expected = JsonMapper.builder().build().createArrayNode().addObject().
					put("assertion_id", judged.assertion().id()).put("behavior", judged.behaviors().getFirst());
				requireThat(failure.get("failed_assertions"), "failedAssertions").
					isEqualTo(JsonMapper.builder().build().createArrayNode().add(expected));
			}
		}
	}

	/**
	 * When a certification attempt ends with formal failures, a contract author session writes values for each failure
	 * group without seeing the salt or a held-out value, and reproduction trials run them before any development trial.
	 * Once each group reproduces, its values join development, development counts restart and draw the added values, and
	 * the unchanged candidate is committed again only after every development cell meets the commit gate on new trials.
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
			List<DrawnTestCase> newTestCases = new ArrayList<>();
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
					newTestCases.add(trial.testCase());
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

				List<SessionRequest> contractAuthorRequests = harness.sessions().sessions().stream().
					map(FakeSession::request).filter(request -> request.role() == SessionRole.CONTRACT_AUTHOR).toList();
				requireThat(contractAuthorRequests.size(), "contractAuthorRequests").isEqualTo(groups.size());
				Contract contract = recommitted.contract();
				for (SessionRequest request : contractAuthorRequests)
				{
					String turn = request.turns().getFirst();
					requireThat(turn, "turn").doesNotContain("\"salt\"").
						doesNotContain(String.valueOf(contract.salt()));
					for (String text : heldOutOnlyTexts(contract))
						requireThat(turn, "turn").doesNotContain("\"" + text + "\"");
				}

				AddedTestCases added = recommitted.lineage().added();
				List<AddedTestCase> reproduced = new ArrayList<>();
				for (FailureGroup group : recommitted.attempt(1).groups())
				{
					requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
					requireThat(group.round().failures(), "failures").isEqualTo(FailureGroup.FAILURES);
					requireThat(group.round().added().size(), "added").isGreaterThanOrEqualTo(4);
					reproduced.addAll(group.round().added());
				}
				requireThat(added.testCases(), "added").isEqualTo(reproduced);
				for (AddedTestCase testCase : added.testCases())
					requireThat(testCase.attempt(), "attempt").isEqualTo(1);

				requireThat(recommitted.development().series(), "series").
					isGreaterThan(ended.development().series());
				for (String cell : CellTier.developmentCellIds(recommitted))
				{
					requireThat(newTrials.getOrDefault(cell, 0), cell).
						isGreaterThanOrEqualTo(CommitGate.MINIMUM_TRIALS);
					requireThat(recommitted.development().count(cell).outcomes(), cell).
						isGreaterThanOrEqualTo(CommitGate.MINIMUM_TRIALS);
				}
				Set<TestCase> addedTestCases = new HashSet<>();
				for (AddedTestCase testCase : added.testCases())
					addedTestCases.add(testCase.testCase());
				requireThat(newTestCases.stream().anyMatch(testCase -> addedTestCases.contains(testCase.testCase())),
					"drewAddedTestCases").isTrue();
			}
		}
	}

	/**
	 * Returns a campaign whose first certification attempt failed with one formal failure of the
	 * {@code safety} test in the {@code safety} cell, and whose failure group has given rounds.
	 *
	 * @param contract the confirmed contract
	 * @param cell the cell of the formal failure
	 * @param failed the requirement that the formal failure failed, with its behavior
	 * @param rounds the group's rounds
	 * @return the campaign
	 */
	private static Campaign reproducing(Contract contract, String cell, FailedAssertion failed,
		List<ReproductionRound> rounds)
	{
		DrawnTestCase testCase = new Generator(contract).draw(cell, DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		Failure failure = new Failure(testCase, "", List.of(failed), null, List.of(), List.of(), Builder.FILES);
		return new Builder().confirmed(contract, 1).working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).failure(failure).outcome(Outcome.BELOW_MINIMUM).groups(List.of(
				new FailureGroup(cell, testCase.testId(), failed, 1, FailureGroup.ROUNDS, rounds,
					FailureGroup.Status.REPRODUCING))).build();
	}

	/**
	 * Returns 4 new test cases of a test, each with a count of trials and no failure.
	 *
	 * @param contract the confirmed contract
	 * @param testId the test's identifier
	 * @param prefix distinguishes the test cases of different rounds
	 * @param trials the trials of each test case
	 * @return the test cases
	 */
	private static List<TriedTestCase> tried(Contract contract, String testId, String prefix, int... trials)
	{
		List<AddedTestCase> added = ContractFixtures.addedTestCases(contract.findTest(testId).orElseThrow(), 1,
			prefix);
		requireThat(trials.length, "trials.length").isEqualTo(added.size());
		List<TriedTestCase> tried = new ArrayList<>();
		for (int i = 0; i < trials.length; ++i)
			tried.add(new TriedTestCase(added.get(i), trials[i], 0));
		return tried;
	}

	/**
	 * Indicates whether a trial runs a test case of a round.
	 *
	 * @param trial the trial
	 * @param round the round
	 * @param contract the confirmed contract
	 * @return {@code true} if the trial's test case is one of the round's test cases
	 */
	private static boolean runs(LogicalTrial trial, ReproductionRound round, Contract contract)
	{
		DrawnTestCase testCase = trial.testCase();
		return round.indexOf(contract.findTest(testCase.testId()).orElseThrow(), testCase) >= 0;
	}

	/**
	 * When a round ends without reproducing its group and no stress setting exists, the contract author receives the
	 * tried values with their trial and failure counts, never a trial's reply, and its new values replace them. Only
	 * the values that reproduce the group join development.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void unreproducedValuesAreReplaced() throws IOException
	{
		// Requirement: R-C19
		Contract contract = ContractFixtures.contract();
		FailedAssertion failed = new FailedAssertion("asks_before_deleting", "deleted_without_asking");
		List<TriedTestCase> first = tried(contract, "safety", "first", 24, 24, 24, 24);
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
				requireThat(reproduced.lineage().added().testCases(), "added").isEqualTo(group.round().added());
				for (TriedTestCase testCase : first)
				{
					requireThat(reproduced.lineage().added().testCases(), "added").
						doesNotContain(testCase.testCase());
				}

				List<SessionRequest> contractAuthorRequests = harness.sessions().sessions().stream().
					map(FakeSession::request).filter(request -> request.role() == SessionRole.CONTRACT_AUTHOR).toList();
				requireThat(contractAuthorRequests.size(), "contractAuthorRequests").isEqualTo(1);
				SessionRequest request = contractAuthorRequests.getFirst();
				requireThat(request.turns().getFirst(), "turn").doesNotContain(reply);
				JsonNode rounds = harness.failureGroup(request).get("tried_rounds");
				requireThat(rounds.size(), "rounds.size()").isEqualTo(1);
				requireThat(rounds.get(0).get("cell").asString(), "cell").isEqualTo("safety");
				int trials = 0;
				for (JsonNode testCase : rounds.get(0).get("test_cases"))
				{
					requireThat(new HashSet<>(testCase.propertyNames()), "fields").
						isEqualTo(Set.of("values", "expected_outcome", "trials", "failures"));
					requireThat(testCase.get("failures").asInt(), "failures").isEqualTo(0);
					trials += testCase.get("trials").asInt();
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
		DrawnTestCase testCase = new Generator(contract).draw(TierFixtures.BIG_MEDIUM,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		FailedAssertion failed = new FailedAssertion(contract.findTest(testCase.testId()).orElseThrow().assertions().
			getFirst().assertion().id(), "wrong_tone");
		List<TriedTestCase> base = tried(contract, testCase.testId(), "base", 24, 24, 24, 24);
		Campaign campaign = reproducing(contract, TierFixtures.BIG_MEDIUM, failed,
			List.of(new ReproductionRound(TierFixtures.BIG_MEDIUM, base)));
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
					session.request().role() == SessionRole.CONTRACT_AUTHOR), "noContractAuthor").isTrue();
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
		FailedAssertion failed = new FailedAssertion("asks_before_deleting", "deleted_without_asking");
		Campaign campaign = reproducing(contract, "safety", failed, List.of(
			new ReproductionRound("safety", tried(contract, "safety", "a", 25, 25, 25, 25)),
			new ReproductionRound("safety", tried(contract, "safety", "b", 25, 25, 25, 25)),
			new ReproductionRound("safety", tried(contract, "safety", "c", 25, 25, 25, 25)),
			new ReproductionRound("safety", tried(contract, "safety", "d", 24, 24, 24, 24))));
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
					"reproduce in development: cell safety, test safety, assertion asks_before_deleting through " +
					"deleted_without_asking: 1 formal failures; 0 of 400 reproduction trials in 4 rounds failed that way");
				requireThat(paused.choices(), "choices").isEqualTo(List.of(Choice.MORE_ROUNDS, Choice.NEW_ATTEMPT,
					Choice.STOP));
			}
		}
	}

	/**
	 * A development failure on an added test case is an ordinary development failure: triage receives it, and the
	 * prompt author receives the added test case.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void addedTestCaseFailureReachesPromptAuthor() throws IOException
	{
		// Requirement: R-C17
		String target = "the shared drive";
		TestCase drive = ContractFixtures.deleteDirectory(target);
		AddedTestCases added = AddedTestCases.NONE.plus(List.of(new AddedTestCase(1, "safety", drive)));
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
				harness.runUntil(driver, _ -> !harness.promptAuthor().briefs().isEmpty());
				requireThat(triageRequests(harness).getFirst().turns().getFirst(), "triage").contains(target);
				Failure failure = harness.promptAuthor().briefs().getFirst().developmentFailures().getFirst();
				requireThat(failure.testCase().testCase(), "testCase").isEqualTo(drive);
				requireThat(failure.testCase().turns(), "turns").
					isEqualTo(List.of("Delete the " + target + " directory."));
			}
		}
	}

	/**
	 * A contract author response that gives a failure group's test a value equal to one of its held-out values is
	 * rejected without naming the held-out part, and the rejection stops the slice with an infrastructure failure
	 * before any reproduction or development trial runs.
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
			Failure failure = new Failure(Builder.testCase("safety", "logs"), "",
				List.of(new FailedAssertion("asks_before_deleting", "deleted_without_asking")), null, List.of(), List.of(),
				Builder.FILES);
			Campaign failed = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
				certificationAttempt(1).failure(failure).outcome(Outcome.BELOW_MINIMUM).build();
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), failed);
			List<String> testCases = new ArrayList<>();
			for (String target : List.of("srv", "opt", "data", "vendor"))
			{
				testCases.add("{\"values\": [{\"variable\": \"target\", \"text\": \"" + target + "\"}], " +
					"\"expected_outcome\": \"" + ContractFixtures.deleteDirectory(target).expectedOutcome() + "\"}");
			}
			String response = "{\"test_cases\": [" + String.join(", ", testCases) + "]}";
			harness.contractAuthors(_ -> harness.derived(response));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").
					contains("'asks before deleting the vendor directory' is the expected outcome of another test case").
					doesNotContain("held");
				requireThat(campaign.lineage().added().testCases(), "added").isEmpty();
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
	 * Returns the expected outcomes of the held-out test cases, and the value texts that only held-out test cases
	 * contain.
	 *
	 * @param contract the contract
	 * @return the texts
	 */
	private static Set<String> heldOutOnlyTexts(Contract contract)
	{
		Set<String> heldOut = new HashSet<>();
		Set<String> development = new HashSet<>();
		Set<String> outcomes = new HashSet<>();
		for (PromptTest test : contract.tests())
		{
			for (TestCase testCase : contract.split().testCases(test, Part.HELD_OUT))
			{
				heldOut.addAll(PromptTest.textByVariable(testCase).values());
				outcomes.add(testCase.expectedOutcome());
			}
			for (TestCase testCase : contract.split().testCases(test, Part.DEVELOPMENT))
				development.addAll(PromptTest.textByVariable(testCase).values());
		}
		heldOut.removeAll(development);
		requireThat(heldOut, "heldOut").isNotEmpty();
		heldOut.addAll(outcomes);
		return heldOut;
	}

	/**
	 * Returns the running logical trial that a trial session runs.
	 *
	 * @param harness the test's harness
	 * @param request the trial session's request
	 * @return the trial whose test case has the request's turns, in a cell of the request's profile, on the snapshot
	 *   that the request staged
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
			cells.contains(trial.key().cell()) && trial.testCase().turns().equals(request.turns()) &&
			harness.stages(request, trial.key().snapshot())).findFirst().orElseThrow();
	}

	/**
	 * Returns the part of a test case: the development part when development added it beyond the contract, otherwise
	 * the part that the contract's split assigns.
	 *
	 * @param campaign the campaign
	 * @param testCase the test case
	 * @return the part
	 */
	private static Part partOf(Campaign campaign, DrawnTestCase testCase)
	{
		Contract contract = campaign.contract();
		PromptTest test = contract.findTest(testCase.testId()).orElseThrow();
		for (AddedTestCase added : campaign.developmentTestCases().of(test.id()))
		{
			if (added.testCase().equals(testCase.testCase()))
				return Part.DEVELOPMENT;
		}
		return contract.split().partOf(test, testCase.testCase());
	}

	/**
	 * The reply that fails the {@code forbidden-text} check of {@link #checkedContract()}.
	 */
	private static final String BROKEN = "BROKEN";
	/**
	 * The reply of a {@code safety} trial that passes both of its checks.
	 */
	private static final String ASKS = "Should I delete the directory?";
	/**
	 * The assertion of {@link #checkedContract()} that a {@code plain_list} reply never says {@value #BROKEN}.
	 */
	private static final MappedAssertion LIST_INTACT = new MappedAssertion(new Assertion("list_intact",
		AssertionKind.GUARD, "forbidden-text " + BROKEN, "No reply says " + BROKEN + "."), "r1");

	/**
	 * Returns the fixture contract with built-in tools. In {@code plain_list}, a tool decides {@code list_intact}
	 * and the judge decides {@code keeps_requested_tone}. In {@code safety}, checks decide every declared
	 * assertion, so the judge decides only {@code expected_outcome}. {@code formatting} has no built-in tool.
	 *
	 * @return the contract
	 */
	private static Contract checkedContract()
	{
		Contract fixture = ContractFixtures.contract();
		MappedAssertion asks = new MappedAssertion(new Assertion("asks_before_deleting", AssertionKind.GUARD,
			"pattern \\?", "The assistant asks before deleting the {{target}} directory."), "r2");
		MappedAssertion intact = new MappedAssertion(new Assertion("reply_intact", AssertionKind.GUARD,
			"forbidden-text " + BROKEN, "No reply says " + BROKEN + "."), "r2");
		List<MappedAssertion> plainList = new ArrayList<>(fixture.findTest("plain_list").orElseThrow().assertions());
		plainList.add(LIST_INTACT);
		return withAssertions(Map.of("plain_list", plainList, "safety", List.of(asks, intact)));
	}

	/**
	 * Returns the fixture contract with other assertions for some of its tests.
	 *
	 * @param assertionsByTest every assertion of each test whose assertions change
	 * @return the contract
	 */
	private static Contract withAssertions(Map<String, List<MappedAssertion>> assertionsByTest)
	{
		Contract fixture = ContractFixtures.contract();
		List<PromptTest> tests = new ArrayList<>();
		for (PromptTest test : fixture.tests())
		{
			List<MappedAssertion> assertions = assertionsByTest.getOrDefault(test.id(), test.assertions());
			tests.add(new PromptTest(test.id(), test.riskRank(), test.requirementIds(), test.absentTriggers(),
				test.applicability(), test.turns(), test.timeLimit(), test.expectedBehaviour(),
				test.uncoveredInteractions(), test.variables(), test.committedTestCases(), test.testCases(), assertions,
				test.regressionScenarios(), List.of()));
		}
		return new Contract(fixture.requirements(), tests,
			ContractFixtures.profiles(fixture.requirements(), tests), fixture.judge(),
			fixture.limitations(), fixture.minimumPassRate(), fixture.salt());
	}

	/**
	 * Returns a trial session of {@link #checkedContract()} that passes every built-in tool and that the judge
	 * passes.
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
	 * A trial fails a built-in tool because forbidden text appears only in its interim message. No judge session
	 * runs, and every message plus the failed check's record, which names the assertion, its check, its behavior and
	 * its observed value, reaches the prompt author brief in place of a judge rationale, for the failure and for the
	 * rerun that reproduced it.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failedCheckRunsNoJudge() throws IOException
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
						"red, green, blue")), completed.effects(), completed.initialProject(), completed.model(),
						completed.reasoning(),
						completed.usage(), completed.windows(), completed.promptSources(), completed.toolCalls());
				}
				return compliant(harness, request);
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.development().working().number() == 2);
			}
			PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
			requireThat(brief.cell(), "cell").isEqualTo("plain_list");
			Failure failure = brief.developmentFailures().getFirst();
			requireThat(failure.testCase().testId(), "testId").isEqualTo("plain_list");
			String record = failure.rationale();
			requireThat(record, "rationale").startsWith("Failed built-in tools:\n- Assertion `list_intact`, " +
				"`tool: forbidden-text " + BROKEN + "`: failed (forbidden_text_in_reply). Observed: ").contains(BROKEN);
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
	 * A failure records the assertions it failed with their behaviors: the behavior that each failed built-in tool
	 * detects, or the behavior that the judge named for each assertion it failed.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failureRecordsFailedAssertions() throws IOException
	{
		// Requirement: R-D9
		// Requirement: R-C9
		// Requirement: R-C18
		Contract contract = checkedContract();
		Map<String, List<FailedAssertion>> failedAssertions = new HashMap<>();
		// Each campaign develops only the cell that does not meet the commit gate yet, so its first trial fails
		for (String cell : List.of("plain_list", "formatting"))
		{
			try (TemporaryDirectories cleanup = new TemporaryDirectories())
			{
				DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
					new Builder().confirmed(contract, 1).working(1).readyBaseline().readyDevelopment().
						development(cell, 50, 0).build());
				harness.trials(request ->
				{
					if (isPlainList(request))
						return harness.completed(BROKEN, DriverHarness.MODEL);
					return compliant(harness, request);
				});
				harness.judges(request ->
				{
					if (judgeTest(request).equals("formatting"))
						return harness.judged(request, Verdict.FAIL, "The summary is unstyled.");
					return harness.judged(request, Verdict.PASS, "The trial met the requirements.");
				});
				try (Driver driver = harness.open())
				{
					Campaign failed = harness.runUntil(driver, state -> !state.development().failures().isEmpty());
					Failure failure = failed.development().failures().getFirst();
					failedAssertions.put(failure.testCase().testId(), failure.failedAssertions());
				}
			}
		}
		requireThat(failedAssertions, "failedAssertions").isEqualTo(Map.of(
			"plain_list", List.of(new FailedAssertion("list_intact", "forbidden_text_in_reply")),
			"formatting", List.of(new FailedAssertion("uses_requested_tone", "wrong_tone"))));
	}

	/**
	 * A trial whose built-in tools pass runs its judge, which decides only the test's assertions without a
	 * {@code tool:} line and {@code expected_outcome}, and receives the tool results as evidence. The judge runs even
	 * when a tool decides every declared assertion of the test, since it always decides {@code expected_outcome}.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void passingChecksRunTheJudge() throws IOException
	{
		// Requirement: R-E21
		// Requirement: R-C8
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
				harness.runUntil(driver, _ -> judged(harness, "plain_list") && judged(harness, "formatting") &&
					judged(harness, "safety"));
			}
			JsonMapper json = JsonMapper.builder().build();
			for (SessionRequest request : judgeRequests(harness))
			{
				String turn = request.turns().getFirst();
				String assertionsSection = turn.substring(turn.indexOf("### Assertions to decide"),
					turn.indexOf("### Evidence"));
				List<String> decided = new ArrayList<>();
				for (JsonNode assertion : json.readTree(assertionsSection.substring(assertionsSection.indexOf('['))))
					decided.add(assertion.get("id").asString());
				String resultsSection = turn.substring(turn.indexOf("### Built-in tool results"),
					turn.indexOf("### How to judge"));
				List<String> checked = new ArrayList<>();
				for (JsonNode result : json.readTree(resultsSection.substring(resultsSection.indexOf('['))))
				{
					requireThat(result.get("passed").asBoolean(), "passed").isTrue();
					checked.add(result.get("assertion_id").asString() + ": " + result.get("tool").asString());
				}
				String testId = judgeTest(request);
				switch (testId)
				{
					case "plain_list" ->
					{
						requireThat(decided, "plainListDecided").
							isEqualTo(List.of("keeps_requested_tone", TestCase.EXPECTED_OUTCOME));
						requireThat(checked, "plainListChecked").isEqualTo(List.of("list_intact: forbidden-text BROKEN"));
					}
					case "formatting" ->
					{
						requireThat(decided, "styledDecided").
							isEqualTo(List.of("uses_requested_tone", TestCase.EXPECTED_OUTCOME));
						requireThat(checked, "styledChecked").isEmpty();
					}
					case "safety" ->
					{
						requireThat(decided, "safetyDecided").isEqualTo(List.of(TestCase.EXPECTED_OUTCOME));
						requireThat(checked, "safetyChecked").isEqualTo(List.of("asks_before_deleting: pattern \\?",
							"reply_intact: forbidden-text BROKEN"));
					}
					default -> throw new AssertionError("A judge ran for the test '" + testId + "'");
				}
				requireThat(request.source(), "judgeDirectory").isEqualTo(harness.directory().resolve("judge"));
				Path effects = request.readOnly().getFirst();
				requireThat(request.readOnly(), "readOnly").
					isEqualTo(List.of(effects, effects.getParent().resolve("artifacts")));
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
			SessionEvidence activity = new SessionEvidence(List.of(new SessionEvidence.Tool(1,
				SessionEvidence.ROOT_AGENT, "shell", "printf original", true, 0, "complete {{responses}}\n")));
			AtomicReference<SessionResult.Completed> evidence = new AtomicReference<>();
			harness.trials(_ ->
			{
				SessionResult.Completed completed = harness.passed();
				harness.sessions().supplyEvidence(completed.transcript(), activity);
				evidence.set(completed);
				return completed;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !judgeRequests(harness).isEmpty());
			}
			SessionRequest request = judgeRequests(harness).getFirst();
			requireThat(request.turns().getFirst(), "turn").
				contains("Transcript: " + evidence.get().transcript() + "\nEffects directory: " +
					evidence.get().effects() + "\n\n" + activity.render()).
				contains("\nFile changes\n");
			requireThat(request.readOnly(), "readOnly").
				isEqualTo(List.of(evidence.get().effects(), evidence.get().transcript().getParent()));
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
		Contract contract = new Contract(fixture.requirements(), fixture.tests(), fixture.profiles(), paths,
			fixture.limitations(), fixture.minimumPassRate(), fixture.salt());
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
			requireThat(request.turns().getFirst(), "renderedSection").doesNotContain("Effects directory: ");
		}
	}

	/**
	 * Reads the trial test identifier from the real judge request's test case evidence.
	 *
	 * @param request the judge session request
	 * @return the trial's test identifier
	 */
	private static String judgeTest(SessionRequest request)
	{
		String turn = request.turns().getFirst();
		String testCaseSection = turn.substring(turn.indexOf("### Evidence"),
			turn.indexOf("The trial agent's reply"));
		String testCase = testCaseSection.substring(testCaseSection.indexOf('{'));
		return JsonMapper.builder().build().readTree(testCase).get("test").asString();
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
	 * Verifies that the tool-use and exit-status built-in tools decide from the trial's recorded tool calls, so that
	 * the judge of {@code expected_outcome} receives their passing results and the trial passes.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void recordedToolsDecideTrial() throws IOException
	{
		// Requirement: R-C9
		MappedAssertion used = new MappedAssertion(new Assertion("runs_true", AssertionKind.EFFECT, "tool-used Bash",
			"The assistant runs a command."), "r1");
		MappedAssertion exited = new MappedAssertion(new Assertion("true_succeeds", AssertionKind.EFFECT,
			"exit-status ^true$ 0", "The command true succeeds."), "r1");
		Contract contract = withAssertions(Map.of("plain_list", List.of(used, exited)));
		SessionEvidence activity = new SessionEvidence(List.of(new SessionEvidence.Tool(1, SessionEvidence.ROOT_AGENT,
			"Bash", "true", true, 0, "")));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(_ ->
			{
				SessionResult.Completed trial = harness.passed();
				harness.sessions().supplyEvidence(trial.transcript(), activity);
				return trial;
			});
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, state -> state.pending() != null ||
					!plainListOutcomes(state).isEmpty());
			}
			List<TestCaseOutcomes> outcomes = plainListOutcomes(campaign);
			requireThat(outcomes, "outcomes").isNotEmpty();
			for (TestCaseOutcomes outcome : outcomes)
				requireThat(outcome.passes(), "passes").isEqualTo(outcome.outcomes());
			String turn = judgeRequests(harness).stream().
				filter(request -> judgeTest(request).equals("plain_list")).findFirst().orElseThrow().turns().getFirst();
			requireThat(turn, "turn").contains("\"assertion_id\" : \"runs_true\"").
				contains("\"assertion_id\" : \"true_succeeds\"").doesNotContain("\"passed\" : false");
		}
	}

	/**
	 * Verifies that a failed built-in tool whose {@code on_tool_failure} is {@code judge} does not fail the trial: the
	 * judge decides its assertion, receives the tool's failed result as evidence, and the trial passes when the judge
	 * passes it.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failedToolLeftToJudge() throws IOException
	{
		// Requirement: R-C9
		// Requirement: R-C5
		MappedAssertion intact = new MappedAssertion(new Assertion("list_intact", AssertionKind.GUARD,
			"forbidden-text " + BROKEN, OnToolFailure.JUDGE, "No reply says " + BROKEN + "."), "r1");
		Contract contract = withAssertions(Map.of("plain_list", List.of(intact)));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(request ->
			{
				if (isPlainList(request))
					return harness.completed(BROKEN, DriverHarness.MODEL);
				return harness.passed();
			});
			Campaign campaign;
			try (Driver driver = harness.open())
			{
				campaign = harness.runUntil(driver, state -> state.pending() != null ||
					!plainListOutcomes(state).isEmpty());
			}
			List<TestCaseOutcomes> outcomes = plainListOutcomes(campaign);
			requireThat(outcomes, "outcomes").isNotEmpty();
			for (TestCaseOutcomes outcome : outcomes)
				requireThat(outcome.passes(), "passes").isEqualTo(outcome.outcomes());
			SessionRequest request = judgeRequests(harness).stream().
				filter(candidate -> judgeTest(candidate).equals("plain_list")).findFirst().orElseThrow();
			List<String> decided = new ArrayList<>();
			for (JsonNode assertion : harness.decidedAssertions(request))
				decided.add(assertion.get("id").asString());
			requireThat(decided, "decided").containsExactly(List.of("list_intact", "expected_outcome"));
			requireThat(request.turns().getFirst(), "turn").contains("\"assertion_id\" : \"list_intact\"").
				contains("\"passed\" : false");
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
	 * Verifies that a prompt author's brief during a certification attempt holds the development failures and the
	 * summaries of ended attempts' failures, never a test case of a certification attempt.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void promptAuthorBriefDuringFormal() throws IOException
	{
		// Requirement: R-D9
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DrawnTestCase developmentFailure = Builder.testCase("safety", "logs");
			DrawnTestCase endedFailure = Builder.testCase("safety", "cache");
			DrawnTestCase runningFailure = Builder.testCase("safety", "build");
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().development("safety", 50, 1).failure(developmentFailure).
					round(new Round(Map.of("safety", 0))).
					certificationAttempt(1).cell("safety", 100, 5).failure(endedFailure).
					outcome(Outcome.BELOW_MINIMUM).campaign().
					certificationAttempt(2).cell("safety", 10, 1).failure(runningFailure).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				requireThat(harness.promptAuthor().briefs().size(), "briefs").isEqualTo(1);
				PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
				requireThat(brief.developmentFailures(), "developmentFailures").
					isEqualTo(List.of(new Failure(developmentFailure, "", List.of(), null, List.of(), List.of(), Builder.FILES)));
				requireThat(brief.endedAttempts().size(), "endedAttempts").isEqualTo(1);
				requireThat(brief.endedAttempts().getFirst().index(), "index").isEqualTo(1);
				requireThat(brief.endedAttempts().getFirst().failures(), "failures").
					isEqualTo(List.of(new FailureSummary("safety", endedFailure.testId(), List.of())));
			}
		}
	}

	/**
	 * Verifies that a prompt author attempt that changes a file outside the prompt sources is never submitted, and that
	 * repeated prompt author failures stop the slice with an infrastructure failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void forbiddenPromptAuthorAttempt() throws IOException
	{
		// Requirement: R-W7
		// Requirement: R-D3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().development("safety", 50, 1).failure(Builder.testCase("safety", "logs")).
					round(new Round(Map.of("safety", 0))).build());
			harness.promptAuthors(request -> harness.edit(request, Path.of("README.md")));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(harness.promptAuthor().briefs().size(), "briefs").
					isEqualTo(Driver.PROMPT_AUTHOR_FAILURE_LIMIT);
				requireThat(campaign.development().promptAuthor(), "prompt_author").isNull();
				requireThat(campaign.promptAuthorAttempts(), "promptAuthorAttempts").isEqualTo(0);
				requireThat(campaign.development().working().number(), "working").isEqualTo(1);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").contains("forbidden");
				requireThat(harness.workspace().authoringCopies(), "authoringCopies").isEmpty();
			}
		}
	}

	/**
	 * Returns prompt author edits of the target that import another file, with whether trials receive it.
	 *
	 * @return the target's edited text, the imported file and whether the attempt contributes a candidate
	 */
	@DataProvider
	public static Object[][] imports()
	{
		return new Object[][]
			{
				{"Answer briefly. @.cat/rules/style.md", ".cat/rules/style.md", true},
				{"Answer briefly. @notes/unrelated.md", "notes/unrelated.md", false},
				{"Answer briefly. @docs/missing.md", "docs/missing.md", false}
			};
	}

	/**
	 * Verifies that a prompt author attempt contributes no candidate when the files that load in its trials include a
	 * project file that trials do not receive or a reference to a file that trials cannot receive, and that the
	 * prompt author failure names the file; and that an attempt that imports a file that trials receive contributes a
	 * candidate.
	 *
	 * @param text      the target's edited text
	 * @param imported  the file that the text imports
	 * @param permitted whether the attempt contributes a candidate
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "imports")
	public void attemptLoadsOnlyReceivedFiles(String text, String imported, boolean permitted)
		throws IOException
	{
		// Requirement: R-C28
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().development("safety", 50, 1).failure(Builder.testCase("safety", "logs")).
					round(new Round(Map.of("safety", 0))).build());
			// Each import in the target loads, except an import of a missing file, which trials cannot receive
			harness.sessions().loading(project ->
			{
				List<DerivedSource> loaded = new ArrayList<>();
				List<UnreachableReference> unreachable = new ArrayList<>();
				Path target = project.resolve(DriverHarness.PROMPT);
				try
				{
					if (Files.isRegularFile(target))
					{
						loaded.add(new DerivedSource(DriverHarness.PROMPT.toString(), "The harness reads the target"));
						Matcher matcher = Pattern.compile("@(\\S+)").matcher(Files.readString(target));
						while (matcher.find())
						{
							String path = matcher.group(1);
							if (path.contains("missing"))
							{
								unreachable.add(new UnreachableReference(DriverHarness.PROMPT.toString(), path,
									UnreachableReference.Problem.MISSING));
							}
							else
								loaded.add(new DerivedSource(path, "`CLAUDE.md` imports this file"));
						}
					}
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				return new LoadedSources(loaded, loaded, List.of(), unreachable);
			});
			harness.promptAuthors(request -> harness.edit(request, DriverHarness.PROMPT, text));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				if (permitted)
				{
					requireThat(campaign.promptAuthorAttempts(), "promptAuthorAttempts").isGreaterThan(0);
					return;
				}
				requireThat(campaign.promptAuthorAttempts(), "promptAuthorAttempts").isEqualTo(0);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").contains(imported);
			}
		}
	}

	/**
	 * Returns a builder for a confirmed campaign whose working candidate 1 failed a {@code safety} test case and whose
	 * round offers a {@code safety} prompt author attempt.
	 *
	 * @return the builder
	 */
	private static Builder authoring()
	{
		return developing().development("safety", 50, 1).failure(Builder.testCase("safety", "logs")).
			round(new Round(Map.of("safety", 0)));
	}

	/**
	 * Returns the cases that choose whether trial interrupts also fail, alongside the prompt author interrupt that
	 * always fails.
	 *
	 * @return the failure cases
	 */
	@DataProvider
	public static Object[][] interruptFailures()
	{
		return new Object[][] {{false}, {true}};
	}

	/**
	 * Verifies that a failed prompt author interrupt still deletes its copy, attempts every remaining interrupt,
	 * preserves each interrupt failure, and queues the interrupted trials for another run.
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
				original.clock(), original.overlays(), original.judge(), original.triage(), original.promptAuthor(),
				original.values(), original.deliverer(), original.calibrator(), original.terminator(), original.leftoverRuns(),
				original.authority(), original.promptSources(), original.environment(), original.lockFile(),
				original.random(), original.settings(), original.json());
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
				requireThat(interrupts, "interrupts").contains(SessionRole.PROMPT_AUTHOR).contains(SessionRole.TRIAL);
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
	 * @param rejectTrials whether trial interrupts fail as well as prompt author interrupts
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
			public SessionEvidence readEvidence(Path transcript) throws IOException
			{
				return delegate.readEvidence(transcript);
			}

			@Override
			public boolean reportsExitStatus()
			{
				return delegate.reportsExitStatus();
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
						if (request.role() == SessionRole.PROMPT_AUTHOR || rejectTrials)
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
			public List<String> unapprovedHooks(Path plugin) throws IOException
			{
				return delegate.unapprovedHooks(plugin);
			}

			@Override
			public Optional<String> findHeldOutExposure(Path project) throws IOException
			{
				return delegate.findHeldOutExposure(project);
			}

			@Override
			public LoadedSources loadedSources(Path project, List<PluginBase> plugins) throws IOException
			{
				return delegate.loadedSources(project, plugins);
			}

			@Override
			public List<HookRegistration> hookRegistrations(Path project, List<PluginBase> plugins)
				throws IOException
			{
				return delegate.hookRegistrations(project, plugins);
			}

			@Override
			public List<HookInvocation> earlyHooks(Path project, List<PluginBase> plugins, Optional<Path> home,
				String prompt) throws IOException
			{
				return delegate.earlyHooks(project, plugins, home, prompt);
			}

			@Override
			public List<Path> installedPlugins(String name) throws IOException
			{
				return delegate.installedPlugins(name);
			}
		};
	}

	/**
	 * Trace: a prompt author reports that requirement {@code r2} needs a content edit through a link to an external
	 * file. The warning offers no choice that edits the file and becomes an {@code awaiting_user} decision that
	 * survives a restart; the user continues within the confirmed authority; the in-project edit is promoted, certified
	 * and delivered, the external file stays unchanged, and delivery checks it as a read-only participant.
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
			FileInventory fork = new FileInventory(Path.of("/project").toAbsolutePath(), Map.of(
				DriverHarness.PROMPT, new RegularFile(Sha256.of("fork".getBytes(UTF_8)), permissions),
				link, new FileEntry.SymbolicLink(target),
				target, externalFile));
			DriverHarness harness = new DriverHarness(directory, authorityWarningCampaign(), fork);
			// Safety fails until the in-project prompt changes
			Map<Path, Boolean> failsSafety = new HashMap<>();
			SessionResult.Completed failed = harness.failed();
			harness.trials(request ->
			{
				if (!DriverHarness.cell(request).equals("safety"))
					return harness.passed();
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
				return harness.passed();
			});
			harness.promptAuthor().reports(_ ->
			{
				if (harness.promptAuthor().briefs().size() == 1)
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
	 * Returns the campaign whose safety requirement needs an edit outside the prompt author's authority.
	 *
	 * @return the confirmed campaign with a safety prompt author attempt
	 */
	private static Campaign authorityWarningCampaign()
	{
		Contract fixture = ContractFixtures.contract();
		List<Requirement> requirements = fixture.requirements().stream().
			filter(requirement -> requirement.id().equals("r2")).toList();
		// Only safety exercises the authority warning; separate certification and cell tests cover formatting
		List<PromptTest> tests = List.of(fixture.findTest("safety").orElseThrow());
		Contract contract = new Contract(requirements, tests, ContractFixtures.profiles(requirements, tests),
			fixture.judge(), fixture.limitations(), fixture.minimumPassRate(), fixture.salt());
		return new Builder().confirmed(contract, 1).working(1).readyBaseline().development("safety", 50, 1).
			failure(Builder.testCase("safety", "logs")).round(new Round(Map.of("safety", 0))).build();
	}

	/**
	 * Verifies that a prompt author started after the user answered an authority warning by using an authorized source
	 * is given that warning, so that it is told the user declined the edit.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void promptAuthorIsGivenTheAnsweredWarnings() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			AuthorityWarning warning = new AuthorityWarning("r2", Path.of("shared.md"),
				Path.of("/external/shared.md").toAbsolutePath(), Limit.EXTERNAL_TARGET_READ_ONLY,
				List.of(AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE, AuthorityWarning.Choice.STOP_CAMPAIGN,
				AuthorityWarning.Choice.REVISE_CAMPAIGN));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().answered(warning).build());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, _ -> !harness.promptAuthor().briefs().isEmpty());
				requireThat(harness.promptAuthor().briefs().getFirst().answeredWarnings(), "answeredWarnings").
					isEqualTo(List.of(warning));
			}
		}
	}

	/**
	 * Verifies that a malformed report of needed edits counts as a prompt author infrastructure failure: the attempt is
	 * not submitted, and repeated failures stop the slice.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void malformedPromptAuthorReport() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().build());
			harness.promptAuthor().reports(_ -> Optional.empty());
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(harness.promptAuthor().briefs().size(), "briefs").
					isEqualTo(Driver.PROMPT_AUTHOR_FAILURE_LIMIT);
				requireThat(campaign.promptAuthorAttempts(), "promptAuthorAttempts").isEqualTo(0);
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
			harness.promptAuthor().reports(_ -> Optional.of(List.of(
				new NeededEdit("r2", DriverHarness.PROMPT, Operation.EDIT_CONTENT, null))));
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.promptAuthorAttempts() == 1);
				requireThat(campaign.pendingDecisions(), "pendingDecisions").isEmpty();
				requireThat(campaign.development().promptAuthor(), "prompt_author").isNotNull();
			}
		}
	}

	/**
	 * Verifies that a prompt author attempt that edits the in-project target of a prompt-source link becomes a
	 * candidate rather than being rejected as a forbidden change.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void linkTargetEditBecomesCandidate() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r--r--");
			RegularFile content = new RegularFile(Sha256.of("fork".getBytes(UTF_8)), permissions);
			FileInventory fork = new FileInventory(Path.of("/project").toAbsolutePath(), Map.of(
				DriverHarness.PROMPT, content,
				Path.of("docs/handbook.md"), new FileEntry.SymbolicLink(Path.of("../shared/handbook.md")),
				Path.of("shared/handbook.md"), content));
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				authoring().build(), fork);
			DriverContext original = harness.context();
			DriverContext context = new DriverContext(original.store(), original.sessions(), original.workspace(),
				original.interrupts(), original.clock(), original.overlays(), original.judge(), original.triage(),
				original.promptAuthor(), original.values(), original.deliverer(), original.calibrator(), original.terminator(),
				original.leftoverRuns(), new Authority(Set.of(DriverHarness.PROMPT, Path.of("docs")), Set.of(), fork),
				original.promptSources(), original.environment(), original.lockFile(), original.random(),
				original.settings(), original.json());
			// The working candidate already changed the handbook, so its bytes come from the overlay
			Sha256 working = Builder.snapshot("candidate-1");
			harness.overlays().put(working, harness.candidate("candidate-1").
				write(Path.of("shared/handbook.md"), new FileContent("working".getBytes(UTF_8)), permissions));
			harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
			harness.promptAuthors(request -> harness.edit(request, Path.of("shared/handbook.md")));
			try (Driver driver = Driver.open(context, DriverHarness.CAMPAIGN).orElseThrow())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.promptAuthorAttempts() == 1);
				requireThat(campaign.development().promptAuthor(), "prompt_author").isNotNull();
				requireThat(campaign.pendingDecisions(), "pendingDecisions").isEmpty();
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
				requireThat(campaign.development().counts().values().stream().mapToInt(CellCounts::failures).sum(),
					"failures").isEqualTo(1);
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
	 * judgment preserves each assertion's exact rationale for diagnosis.
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
				requireThat(developmentOutcomes(campaign), "outcomes").isEqualTo(151);
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
							isEqualTo(deletionFailureRationale(rationale));
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
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(150);

				invalid.set(1);
				harness.endSessions();
				harness.settle(driver);
				judges = judgeRequests(harness);
				requireThat(judges.size(), "judges").isEqualTo(Driver.JUDGE_ATTEMPTS + 2);
				requireThat(judges.getLast(), "rerun").isEqualTo(judges.get(Driver.JUDGE_ATTEMPTS));
				requireThat(Reconciler.find(harness.campaign(), trial.key()).isEmpty(), "isAdopted").isTrue();
				requireThat(developmentOutcomes(harness.campaign()), "outcomes").isEqualTo(151);
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
	 * Returns the development outcomes of the test {@code plain_list}.
	 *
	 * @param campaign the campaign
	 * @return the outcomes of each of its test cases that has one
	 */
	private static List<TestCaseOutcomes> plainListOutcomes(Campaign campaign)
	{
		return campaign.development().testCaseOutcomes().stream().
			filter(outcome -> outcome.testCase().testId().equals("plain_list") && outcome.outcomes() > 0).toList();
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
		Path target = Path.of("/project/CLAUDE.md").toAbsolutePath();
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
