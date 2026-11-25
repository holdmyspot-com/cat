package com.holdmyspot.cat.common.optimizer.execution.test;

import com.holdmyspot.cat.common.optimizer.execution.BackgroundDriver;
import com.holdmyspot.cat.common.fakes.test.Yielding;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Admission;
import com.holdmyspot.cat.common.optimizer.campaign.Allocation;
import com.holdmyspot.cat.common.optimizer.campaign.AllocationRule;
import com.holdmyspot.cat.common.optimizer.campaign.CommandCounts;
import com.holdmyspot.cat.common.optimizer.campaign.AttemptResult;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorApproach;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.Candidate;
import com.holdmyspot.cat.common.optimizer.campaign.CellCounts;
import com.holdmyspot.cat.common.optimizer.campaign.Baseline;
import com.holdmyspot.cat.common.optimizer.campaign.CellTier;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationHistory;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.Choice;
import com.holdmyspot.cat.common.optimizer.campaign.ConcurrencyPolicy;
import com.holdmyspot.cat.common.optimizer.campaign.Pairing;
import com.holdmyspot.cat.common.optimizer.campaign.DecisionTable.CellDecision;
import com.holdmyspot.cat.common.optimizer.campaign.DecisionTable;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryEntry;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryLabel;
import com.holdmyspot.cat.common.optimizer.campaign.DeliveryRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Development;
import com.holdmyspot.cat.common.optimizer.campaign.DiagnosticRerun;
import com.holdmyspot.cat.common.optimizer.campaign.EditOrContinue;
import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.Failure;
import com.holdmyspot.cat.common.optimizer.campaign.FailureGroup;
import com.holdmyspot.cat.common.optimizer.campaign.FailureKind;
import com.holdmyspot.cat.common.optimizer.campaign.FailureSummary;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Impact;
import com.holdmyspot.cat.common.optimizer.campaign.Lifecycle;
import com.holdmyspot.cat.common.optimizer.campaign.LogicalTrial;
import com.holdmyspot.cat.common.optimizer.campaign.Minimality;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.OptimizationEvidence;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.PendingDecision;
import com.holdmyspot.cat.common.optimizer.campaign.Promotion;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionReplay;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.PairCounts;
import com.holdmyspot.cat.common.optimizer.campaign.CommitGate;
import com.holdmyspot.cat.common.optimizer.campaign.ReproductionRound;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Scheduler;
import com.holdmyspot.cat.common.optimizer.campaign.TestCaseOutcomes;
import com.holdmyspot.cat.common.optimizer.campaign.TestRevisionRequest;
import com.holdmyspot.cat.common.optimizer.campaign.Throttle;
import com.holdmyspot.cat.common.optimizer.campaign.TrialAttemptFailure;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TriedTestCase;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.campaign.WorkUnit;
import com.holdmyspot.cat.common.optimizer.campaign.test.Boundaries;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCase;
import com.holdmyspot.cat.common.optimizer.contract.AddedTestCases;
import com.holdmyspot.cat.common.optimizer.contract.Assertion;
import com.holdmyspot.cat.common.optimizer.contract.AssertionKind;
import com.holdmyspot.cat.common.optimizer.contract.Dependent;
import com.holdmyspot.cat.common.optimizer.contract.DerivedSource;
import com.holdmyspot.cat.common.optimizer.contract.EvidenceScope;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.Cell;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;
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
import com.holdmyspot.cat.common.optimizer.contract.TestValueJson;
import com.holdmyspot.cat.common.optimizer.contract.TrialPlugin;
import com.holdmyspot.cat.common.optimizer.contract.WorkspaceFile;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.contract.test.TierFixtures;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveredTest;
import com.holdmyspot.cat.common.optimizer.delivery.test.DeliveredTestContract;
import com.holdmyspot.cat.common.optimizer.contract.ScenarioFormatException;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.delivery.Delivery;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.evidence.CommandSummary;
import com.holdmyspot.cat.common.optimizer.execution.PromptAuthorBrief;
import com.holdmyspot.cat.common.optimizer.execution.CampaignLock;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.DriverContext;
import com.holdmyspot.cat.common.optimizer.execution.NeededEdit;
import com.holdmyspot.cat.common.optimizer.execution.Reconciler;
import com.holdmyspot.cat.common.optimizer.execution.SliceReports;
import com.holdmyspot.cat.common.optimizer.execution.SliceResult;
import com.holdmyspot.cat.common.optimizer.execution.SliceRunner;
import com.holdmyspot.cat.common.optimizer.execution.SliceSettings;
import com.holdmyspot.cat.common.optimizer.execution.SnapshotMismatchException;
import com.holdmyspot.cat.common.optimizer.execution.Step;
import com.holdmyspot.cat.common.optimizer.execution.VerdictKey;
import com.holdmyspot.cat.common.optimizer.execution.VerdictRecord;
import com.holdmyspot.cat.common.optimizer.execution.fixture.test.FaultingFileChannel;
import com.holdmyspot.cat.common.optimizer.inventory.Authority;
import com.holdmyspot.cat.common.optimizer.inventory.AuthorityWarning;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryChange;
import com.holdmyspot.cat.common.optimizer.inventory.DeliveryConflict;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
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
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.SessionResult;
import com.holdmyspot.cat.common.optimizer.port.SessionRole;
import com.holdmyspot.cat.common.optimizer.port.TokenUsage;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort.FakeSession;
import com.holdmyspot.cat.common.optimizer.port.test.FakeWorkspacePort;
import com.holdmyspot.cat.common.optimizer.report.EnumNames;
import com.holdmyspot.cat.common.optimizer.report.CellReport;
import com.holdmyspot.cat.common.optimizer.report.CertifiedEnvironment;
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
import com.holdmyspot.cat.common.optimizer.store.TrialRecovery;
import com.holdmyspot.cat.common.optimizer.store.TrialRecord;
import com.holdmyspot.cat.common.optimizer.store.TrialSession;
import com.holdmyspot.cat.common.optimizer.cli.test.GitlessForks;
import com.holdmyspot.cat.common.runner.AuthenticationEvidence;
import com.holdmyspot.cat.common.runner.HarnessCapacity.Hold;
import com.holdmyspot.cat.common.runner.RetainedTrialClaim;
import com.holdmyspot.cat.common.runner.RetainedTrialRequest;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.HarnessSession;
import com.holdmyspot.cat.common.runner.RetainedTrialResponse.Identity;
import com.holdmyspot.cat.common.runner.RetainedTrialUnresumableException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Covers campaign scenarios from builder state, through the file campaign store and file candidate overlays, the
 * fake session and workspace ports and a manual clock.
 */
public final class DriverTest
{
	/**
	 * The supplied generic plugin rule directory used by trial-plugin mappings and their physical test bases.
	 */
	private static final Path PLUGIN_RULES = Path.of("rules/harness-a");

	/**
	 * Completed candidate and incumbent trials record their observed profiles and report improvement and token medians.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-M1
	// Requirement: R-M4
	// Requirement: R-E18
	// Requirement: R-M7
	@Test
	public void statusComparesObservedTrials() throws IOException
	{
		Map<MeasurementCondition, String> conditions = new EnumMap<>(MeasurementCondition.class);
		conditions.putAll(Builder.confirmedIdentity(DriverHarness.MODEL).values());
		for (MeasurementCondition condition : List.of(MeasurementCondition.JUDGE, MeasurementCondition.MODEL,
			MeasurementCondition.REASONING))
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
	 * Verifies that a shared candidate goes to its assigned peer and retains its own evidence and loader conditions.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void sharedDevelopmentUsesPeer() throws IOException
	{
		// Requirement: R-L8
		Contract shared = sharedContract();
		Header header = sharedHeader();
		Map<MeasurementCondition, String> global = new EnumMap<>(MeasurementCondition.class);
		global.putAll(Builder.confirmedIdentity(DriverHarness.MODEL).values());
		global.remove(MeasurementCondition.LOADER_CONFIGURATION);
		global.remove(MeasurementCondition.PROMPT_SOURCES);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("shared-driver"));
			Path claudeBase = Files.createDirectory(directory.resolve("claude-plugin"));
			Path codexBase = Files.createDirectory(directory.resolve("codex-plugin"));
			Files.writeString(claudeBase.resolve("guide.md"), "Claude fixture");
			Files.writeString(codexBase.resolve("guide.md"), "Codex fixture");
			TrialPlugin claudePlugin = new TrialPlugin("guide@fixture",
				InventoryScanner.scan(claudeBase, Set.of()).identity().hex(), new TrialPlugin.Installed(), List.of(), null);
			TrialPlugin codexPlugin = new TrialPlugin("guide@fixture",
				InventoryScanner.scan(codexBase, Set.of()).identity().hex(), new TrialPlugin.Installed(), List.of(), null);
			Campaign initial = new Builder().header(header).confirmed(shared, 1).working(1).readyBaseline().
				ceiling(1).window(1).identity(new MeasurementIdentity(global)).build().withEvaluatorPlugins(
					Map.of(Harness.CLAUDE, List.of(claudePlugin), Harness.CODEX, List.of(codexPlugin)));
			DriverHarness harness = new DriverHarness(directory, initial);
			Path claudeCopy = harness.workspace().pluginBase(harness.workspace().copyPluginBase(claudeBase));
			Path codexCopy = harness.workspace().pluginBase(harness.workspace().copyPluginBase(codexBase));
			Set<List<Path>> dispatched = new HashSet<>();
			Map<String, TrialKey> firstAssignments = new HashMap<>();
			harness.trials(request ->
			{
				dispatched.add(request.plugins());
				TrialKey key = runningTrial(harness, request).key();
				requireThat(key.purpose(), "purpose").isEqualTo(Purpose.DEVELOPMENT);
				requireThat(key.snapshot(), "assignedSnapshot").isEqualTo(initial.development().working().snapshot());
				firstAssignments.putIfAbsent(key.cell(), key);
				return harness.passed();
			});
			Set<Path> peerTranscripts = new HashSet<>();
			SessionPort peer = new FakeSessionPort()
			{
				@Override
				public SessionHandle start(SessionRequest request) throws IOException
				{
					requireThat(request.plugins(), "peerPlugins").isEqualTo(List.of(claudeCopy));
					return harness.sessions().start(request);
				}

				@Override
				public SessionEvidence readEvidence(Path transcript)
				{
					peerTranscripts.add(transcript);
					return harness.sessions().readEvidence(transcript);
				}
			};
			Map<String, Map<MeasurementCondition, String>> cellConditions = new HashMap<>();
			for (String cellId : shared.cellIds())
			{
				Harness evaluator = shared.findCell(cellId).orElseThrow().evaluator().orElseThrow();
				cellConditions.put(cellId, Map.of(MeasurementCondition.LOADER_CONFIGURATION, evaluator.commandName()));
			}
			DriverContext context = harness.context(harness.sessions(),
				Map.of(Harness.CLAUDE, peer, Harness.CODEX, harness.sessions()), cellConditions);
			Campaign settled;
			try (Driver driver = Driver.open(context, header.id()).orElseThrow())
			{
				settled = harness.runUntil(driver, state -> CellTier.developmentCellIds(state).stream().
					allMatch(cell -> state.development().count(cell).outcomes() > 0));
			}
			requireThat(dispatched, "dispatchedPlugins").containsExactly(Set.of(List.of(claudeCopy), List.of(codexCopy)));
			requireThat(peerTranscripts, "peerTranscripts").isNotEmpty();
			requireThat(settled.identity().values().containsKey(MeasurementCondition.PROMPT_SOURCES),
				"campaignWidePromptSources").isFalse();
			requireThat(firstAssignments.keySet(), "assignedCells").
				isEqualTo(Set.copyOf(CellTier.developmentCellIds(settled)));
			for (String cellId : CellTier.developmentCellIds(settled))
			{
				TrialKey key = firstAssignments.get(cellId);
				CampaignEvent.Complete outcome = recordedOutcome(harness, key).orElseThrow();
				requireThat(outcome.key(), "outcomeKey").isEqualTo(key);
				requireThat(outcome.verdict(), "outcomeVerdict").isEqualTo(Verdict.PASS);
				Map<MeasurementCondition, String> observation = settled.identity().cells().get(cellId);
				requireThat(observation.get(MeasurementCondition.LOADER_CONFIGURATION), "loader").
					isEqualTo(cellConditions.get(cellId).get(MeasurementCondition.LOADER_CONFIGURATION));
				requireThat(observation.get(MeasurementCondition.PROMPT_SOURCES), "promptSources").
					isEqualTo(DriverHarness.PROMPT_SOURCES);
			}
		}
	}

	/**
	 * A provider hold queues its rejected trial while the other evaluator uses the campaign's only slot.
	 * <p>
	 * The driver executes and persists three states in one campaign. One evaluator completes development during the
	 * peer's hold, the peer retries the assigned trial after expiry, and its later refusal ends the slice.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void sharedHoldLeavesOtherEvaluatorRunning() throws IOException
	{
		// Requirement: R-L8
		Contract contract = sharedContract();
		Header header = sharedHeader();
		Campaign initial = new Builder().header(header).confirmed(contract, 1).working(1).readyBaseline().
			ceiling(1).window(1).build();
		String peerCell = CellTier.developmentCells(initial).stream().
			filter(cell -> cell.evaluator().orElseThrow() == Harness.CLAUDE).findFirst().orElseThrow().id();
		Allocation allocation = new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND, initial.evidenceRevision(),
			DriverHarness.START);
		Admission.Assign admission = new Admission.Assign(Purpose.DEVELOPMENT, initial.development().series(),
			initial.development().working().snapshot(), peerCell, null, allocation);
		initial = Transitions.apply(initial, new CampaignEvent.Assign(admission));
		TrialKey peerKey = initial.trials().getLast().key();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("shared-hold")), initial);
			Instant resetsAt = DriverHarness.START.plusSeconds(60);
			AtomicBoolean limited = new AtomicBoolean();
			SessionPort peer = new FakeSessionPort(HostLogin.API_KEY)
			{
				@Override
				public SessionHandle start(SessionRequest request) throws IOException
				{
					return harness.sessions().start(request);
				}

				@Override
				public SessionEvidence readEvidence(Path transcript)
				{
					return harness.sessions().readEvidence(transcript);
				}

				@Override
				public Optional<Instant> sharedHoldUntil(Instant now)
				{
					if (limited.get() && now.isBefore(resetsAt))
						return Optional.of(resetsAt);
					return Optional.empty();
				}
			};
			DriverContext context = harness.context(harness.sessions(),
				Map.of(Harness.CLAUDE, peer, Harness.CODEX, harness.sessions()), Map.of());
			try (Driver driver = Driver.open(context, header.id()).orElseThrow())
			{
				driver.step();
				FakeSession rejected = harness.runningTrials().getFirst();
				limited.set(true);
				rejected.end(new SessionResult.RateLimited(new Hold(resetsAt, 0), List.of()));
				driver.step();

				Campaign held = harness.campaign();
				LogicalTrial queued = Reconciler.find(held, peerKey).orElseThrow();
				requireThat(queued.state(), "heldTrial").isEqualTo(TrialState.ASSIGNED);
				requireThat(queued.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
				requireThat(held.windows(), "windows").isEqualTo(initial.windows());
				TrialSession peerSession = harness.crashingStore().trialSessions(peerKey).getFirst();
				requireThat(peerSession.login(), "peerLogin").isEqualTo(HostLogin.API_KEY);
				List<LogicalTrial> running = trials(held, TrialState.RUNNING);
				requireThat(running.size(), "running").isEqualTo(1);
				Cell other = contract.findCell(running.getFirst().key().cell()).orElseThrow();
				requireThat(other.evaluator().orElseThrow(), "activeEvaluator").isEqualTo(Harness.CODEX);
				harness.runningTrials().getFirst().end(harness.passed());
				harness.settle(driver);
				requireThat(harness.campaign().development().count(other.id()).outcomes(), "outcomes").isPositive();
				TrialSession ownerSession = harness.crashingStore().trialSessions(running.getFirst().key()).getFirst();
				requireThat(ownerSession.login(), "ownerLogin").isEqualTo(HostLogin.UNKNOWN);

				harness.clock().set(resetsAt);
				harness.runningTrials().forEach(trial -> trial.end(harness.passed()));
				harness.runUntil(driver, state -> Reconciler.find(state, peerKey).
					map(trial -> trial.state() == TrialState.RUNNING).orElse(false));
				FakeSession resumed = harness.runningTrials().getFirst();
				assertSameTrialRequest(resumed.request(), rejected.request());
				resumed.end(new SessionResult.Refused(new Hold(resetsAt.plusSeconds(1800), 6), true, List.of()));

				SliceResult stopped = harness.runSlice(driver);
				requireThat(stopped.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(stopped.status().outcomeDetail(), "outcomeDetail").
					contains("claude").contains("30-minute hold");
				LogicalTrial waiting = Reconciler.find(harness.campaign(), peerKey).orElseThrow();
				requireThat(waiting.state(), "waitingTrial").isEqualTo(TrialState.ASSIGNED);
				requireThat(waiting.infrastructureFailures(), "chargedFailures").isEqualTo(0);
				requireThat(harness.crashingStore().trialSessions(peerKey).size(), "peerSessions").isEqualTo(2);
			}
		}
	}

	/**
	 * Returns a fixture contract with a local set of cells in each evaluator.
	 *
	 * @return the contract
	 */
	private static Contract sharedContract()
	{
		Contract base = ContractFixtures.contract();
		TrialProfiles profiles = TrialProfiles.forEvaluators(Map.of(Harness.CLAUDE, base.profiles(),
			Harness.CODEX, base.profiles()));
		return new Contract(base.requirements(), base.tests(), profiles, base.judge(), base.limitations(),
			base.minimumPassRate(), base.salt());
	}

	/**
	 * Returns the header for a shared campaign started from Codex.
	 *
	 * @return the header
	 */
	private static Header sharedHeader()
	{
		Header owner = new Builder().build().header();
		return new Header(owner.id(), owner.root(), owner.target(), Harness.CODEX, owner.lifecycle(),
			owner.goal(), owner.extraPermissions(), Set.of(Harness.CODEX, Harness.CLAUDE));
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
	 * development outcomes in the formatting and safety cells, while every other cell meets the commit gate.
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
			readyDevelopment().development("formatting", 49, 0).development("safety", 49, 0).ceiling(1).build();
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
				new Builder().confirmed().working(1).readyBaseline().readyDevelopment().
					development("formatting", 49, 0).development("safety", 49, 0).ceiling(1).build());
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
			FileCampaignStore store = new FileCampaignStore(directory, JsonMapper.builder().build(), Yielding.fileReplacer());
			store.replaceSnapshot(harness.campaign());
			DriverContext original = harness.context();
			DriverContext context = new DriverContext(original.fileLocks(), store, original.sessions(), original.workspace(),
				original.interrupts(), original.clock(), original.overlays(), original.judge(), original.triage(),
				original.ranking(),
				original.promptAuthor(), original.values(), original.deliverer(), original.calibrator(), original.terminator(),
				original.leftoverSessions(), original.authority(), original.promptSources(), original.environment(),
				original.lockFile(), original.random(), original.settings(), original.json(),
				original.temporaryDirectory());
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
				TokenStatistics reported = StatusReport.of(new FileCampaignStore(directory, JsonMapper.builder().build(),
					Yielding.fileReplacer()).
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
	 * The fixture records one trial, expires the slice with the manual clock, and checks the live summary and the
	 * reloaded campaign report.
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
				harness.runUntil(driver, campaign -> campaign.evidenceRevision() >= 1);
				harness.clock().advance(Duration.ofHours(1));
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
					window(2).build());
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
				harness.runUntil(driver, () -> harness.promptAuthor().briefs().size() == 3);
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
					window(2).build());
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
					window(1).build());
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
					window(1).build());
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
					window(1).build());
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
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());

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
					window(1).build());
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
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());

				PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
				requireThat(brief.developmentFailures().size(), "failures").isEqualTo(1);
				requireThat(brief.evidenceSections(), "evidenceSections").isEmpty();
			}
		}
	}

	/**
	 * Returns the rationale of a judgment that fails the assertion {@code asks_before_deleting}.
	 *
	 * @param reason the judge's reason for the failure
	 * @return the rationale
	 */
	private static String deletionFailureRationale(String reason)
	{
		return "- `asks_before_deleting`: fail (deleted_without_asking). " + reason;
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
				developing().window(1).build());
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
				developing().window(1).build());
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
	 * A weak round compares three independent attempts, minimizes only the best accepted edit and retains the other
	 * evidence.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void weakRoundSelectsBestAttempt() throws IOException
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
				harness.runUntil(driver, () -> harness.promptAuthor().briefs().size() == 3);
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
				requireThat(promoted.optimization().removed(), "removed").isEqualTo(List.of(DriverHarness.PROMPT + "#2"));
				requireThat(promoted.development().counts(), "newCounts").isEmpty();
				requireThat(promoted.attempts(), "allowance").isEmpty();
				ObjectNode status = StatusReport.of(promoted, ReportEvidence.NONE).toJson(JsonMapper.builder().build());
				JsonNode attempts = status.get("prompt_author_attempts");
				requireThat(attempts.size(), "retainedAttempts").isEqualTo(3);
				requireThat(attempts.get(0).get("passing_replays").asInt(), "firstPasses").isEqualTo(2);
				requireThat(attempts.get(0).get("accepted").asBoolean(), "firstAccepted").isTrue();
				requireThat(attempts.get(0).get("winner").asBoolean(), "firstWinner").isFalse();
				requireThat(attempts.get(1).get("passing_replays").asInt(), "secondPasses").isEqualTo(3);
				requireThat(attempts.get(1).get("accepted").asBoolean(), "secondAccepted").isTrue();
				requireThat(attempts.get(1).get("winner").asBoolean(), "secondWinner").isTrue();
				requireThat(attempts.get(2).get("accepted").asBoolean(), "thirdAccepted").isFalse();
				requireThat(attempts.get(2).get("winner").asBoolean(), "thirdWinner").isFalse();
				// The first edit failed the regression test case that the working candidate also fails, so its paired
				// replays ran to the last look; the third failed its triggering test case and ran no regression replay
				requireThat(attempts.get(0).get("total_replays").asInt(), "firstReplays").
					isEqualTo(3 + Promotion.MAXIMUM_PAIRED_REPLAYS);
				requireThat(attempts.get(1).get("total_replays").asInt(), "secondReplays").isEqualTo(3);
				requireThat(attempts.get(2).get("total_replays").asInt(), "thirdReplays").
					isLessThanOrEqualTo(Promotion.STARTING_TRIGGERING_REPLAYS);
				for (JsonNode attempt : attempts)
					harness.overlays().require(new Sha256(attempt.get("snapshot").asString()));
			}
		}
	}

	/**
	 * A round that the development estimate does not make weak starts one prompt author at a time.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void strongRoundStartsOnePromptAuthor() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("strong-round")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					round(new Round(Map.of("safety", 0))).failure(Builder.targeted("safety", "logs")).build());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());
				requireThat(harness.promptAuthor().briefs().size(), "attempts").isEqualTo(1);
				requireThat(harness.running().stream().filter(session ->
					session.request().role() == SessionRole.PROMPT_AUTHOR).count(), "runningAuthors").isEqualTo(1L);
			}
		}
	}


	/**
	 * Restarting a weak round reruns unfinished targeted trials under their original identities and retains completed
	 * attempts without spending certification allowance or adding development evidence.
	 * <p>
	 * This test can take over one second: three prompt-author attempts reach targeted trials before the driver
	 * closes, then a reopened driver resumes those trials through promotion. The three-attempt round and both
	 * driver lifetimes are needed to check retained trial identities and the eventual promotion together.
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
				harness.runUntil(driver, () -> harness.promptAuthor().briefs().size() == 3);
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
	 * All three weak attempts use one working reference's verdict, and that replay does not count as their own evidence.
	 * The reference starts the pooled check of each attempt's failed regression replay, whose paired replays then reject
	 * every attempt where the working candidate passes and accept every attempt where it fails.
	 * <p>
	 * The three prompt-author attempts share one working reference, then each completes regression and paired replays
	 * before the round ends. Retaining those transitions and the persisted campaign lets this test check the single
	 * reference plan, each attempt's own evidence, and the eventual decisions together.
	 * The three attempts each replay regression and paired replays, and every transition persists the campaign to
	 * disk, so the case takes about a second, longer during a parallel build. The test keeps the real persistence
	 * because it asserts the persisted campaign.
	 *
	 * @param referenceVerdict the working candidate's verdict on the regression test case
	 * @param accepted         indicates whether every attempt fixes the failure without introducing a regression
	 * @param pairs            the paired replays of each attempt
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D12
	@Test(dataProvider = "workingReferenceVerdicts")
	public void weakRoundSharesWorkingReference(Verdict referenceVerdict, boolean accepted, int pairs)
		throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = workingReferenceHarness(
				cleanup.add(Files.createTempDirectory("shared-reference")), referenceVerdict);
			try (Driver driver = harness.open())
			{
				Campaign planned = harness.runUntil(driver, state ->
					state.development().round().validationAttempts().size() == 3 &&
						state.development().round().validationAttempts().stream().allMatch(attempt ->
							attempt.replays().stream().anyMatch(replay -> replay.role() == PromotionReplay.Role.REGRESSION)));
				requireThat(planned.development().round().validationAttempts().stream().
					flatMap(attempt -> attempt.replays().stream()).
					filter(replay -> replay.role() == PromotionReplay.Role.WORKING_REFERENCE).count(), "referencePlans").
					isEqualTo(1);
				TrialKey reference = planned.development().round().validationAttempts().stream().
					flatMap(attempt -> attempt.replays().stream()).
					filter(replay -> replay.role() == PromotionReplay.Role.WORKING_REFERENCE).
					map(PromotionReplay::trial).findFirst().orElseThrow();

				Campaign decided = harness.runUntil(driver, state ->
				{
					if (state.development().promptAuthor() != null)
					{
						for (PromotionReplay replay : state.development().promptAuthor().replays())
						{
							if (replay.role() == PromotionReplay.Role.WORKING_REFERENCE)
								requireThat(replay.trial(), "reductionReferenceIdentity").isEqualTo(reference);
						}
					}
					return state.development().round() == null;
				});
				requireThat(decided.optimization().attempts().size(), "decidedAttempts").isEqualTo(3);
				long pairedWorkingReplays = 0;
				for (AttemptResult attempt : decided.optimization().attempts())
				{
					requireThat(attempt.accepted(), "sharedReferenceDecision").isEqualTo(accepted);
					requireThat(attempt.passingReplays(), "ownPassingReplays").isEqualTo(2);
					requireThat(attempt.totalReplays(), "ownReplays").isEqualTo(3L + pairs);
					pairedWorkingReplays += attempt.replays().stream().filter(replay ->
						replay.role() == PromotionReplay.Role.PAIRED_WORKING && replay.verdict() != null).count();
				}
				requireThat(pairedWorkingReplays, "pairedWorkingReplays").isEqualTo(3L * pairs);
				requireThat(decided.attempts(), "certificationAllowance").isEmpty();
			}
		}
	}

	/**
	 * Returns the reference verdicts that reject or accept the same three failed regression replays.
	 *
	 * @return the reference verdict, the expected acceptance for all attempts, and their paired replays
	 */
	@DataProvider
	public static Object[][] workingReferenceVerdicts()
	{
		return new Object[][] {{Verdict.PASS, false, Promotion.PAIRED_REPLAY_STEP},
			{Verdict.FAIL, true, Promotion.MAXIMUM_PAIRED_REPLAYS}};
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
			new Builder().confirmed().working(1).readyBaseline().ceiling(16).window(16).
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
	 * The second sequential reservation uses its ranked combination approach, including after a driver restart.
	 *
	 * The resumed author supplies a distinct edit, and the test waits for its persisted diagnosis before checking
	 * the adopted edit and the complete unchanged reservation map. The initial brief count only stages the restart;
	 * fresh persisted output proves that the resumed work occurred.
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
			String combinationApproach = PromptAuthorApproach.COMBINE_INSTRUCTIONS.text();
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("approach-resume")),
				authoring().build());
			harness.trials(_ -> harness.failed());
			harness.rankings(_ -> harness.ranked(List.of(PromptAuthorApproach.values()).reversed(), false));
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> harness.promptAuthor().briefs().size() == 2);
				SessionRequest second = harness.sessions().sessions().getLast().request();
				requireThat(second.role(), "role").isEqualTo(SessionRole.PROMPT_AUTHOR);
				requireThat(second.turns().getFirst(), "secondApproach").contains(combinationApproach);
			}

			Path savedCampaigns = cleanup.add(Files.createTempDirectory("ranked-round-store"));
			FileCampaignStore store = new FileCampaignStore(savedCampaigns, JsonMapper.builder().build(),
				Yielding.fileReplacer());
			store.replaceSnapshot(harness.campaign());
			harness.store().replaceSnapshot(store.load(DriverHarness.CAMPAIGN).orElseThrow());
			Map<Integer, String> reservations = harness.campaign().development().round().reservations();
			requireThat(reservations, "reservedAttempts").isEqualTo(Map.of(1, "safety", 2, "safety"));
			int resumedAttempt = 2;
			AtomicReference<SessionRequest> resumedRequest = new AtomicReference<>();
			AtomicReference<Path> resumedEffects = new AtomicReference<>();
			harness.promptAuthors(request ->
			{
				resumedRequest.set(request);
				SessionResult.Completed result = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT,
					"resumed combination");
				resumedEffects.set(result.effects());
				return result;
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> state.optimization().promptAuthors().stream().
					anyMatch(diagnosis -> diagnosis.effects().equals(resumedEffects.get())));
				SessionRequest resumed = resumedRequest.get();
				requireThat(resumed, "resumedRequest").isNotNull();
				requireThat(resumed.role(), "role").isEqualTo(SessionRole.PROMPT_AUTHOR);
				requireThat(resumed.turns().getFirst(), "resumedApproach").contains(combinationApproach);
				PromptAuthorDiagnosis diagnosis = campaign.optimization().promptAuthors().stream().
					filter(value -> value.effects().equals(resumedEffects.get())).findFirst().orElseThrow();
				requireThat(diagnosis.number(), "resumedAttempt").isEqualTo(resumedAttempt);
				PromptAuthorAttempt attempt = campaign.development().round().validationAttempts().stream().
					filter(value -> value.number() == resumedAttempt).findFirst().orElseThrow();
				Write prompt = (Write) harness.overlays().require(attempt.snapshot()).entries().get(DriverHarness.PROMPT);
				requireThat(new String(prompt.content().bytes(), UTF_8), "resumedPrompt").isEqualTo("resumed combination");
				requireThat(campaign.development().round().reservations(), "reservations").isEqualTo(reservations);
				requireThat(harness.sessions().sessions().stream().filter(session ->
					session.request().role() == SessionRole.APPROACH_RANKER).count(), "rankingNotRepeated").isEqualTo(1L);
			}
		}
	}

	/**
	 * A failed ranking continues declaration-order rotation from the cell's two earlier prompt author attempts.
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
			harness.rankings(_ -> harness.completed("{}", DriverHarness.JUDGE_MODEL));
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());
				requireThat(harness.promptAuthor().briefs().getFirst().approach(), "approach").
					isEqualTo(PromptAuthorApproach.values()[2]);
			}
		}
	}

	/**
	 * A fresh round ranks its approaches before sending the first prompt author brief.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void ranksBeforePromptAuthor() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("approach-rank")),
				authoring().build());
			harness.trials(_ -> harness.failed());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());
				List<String> roles = harness.sessions().sessions().stream().map(session -> session.request().role().name()).
					toList();
				requireThat(roles, "sessionRoles").contains("APPROACH_RANKER");
			}
		}
	}

	/**
	 * Invalid ranking output is retried unchanged; exhaustion falls back without stopping prompt author work.
	 * Successful rankings assign unique approaches in fit order, independent of declaration order.
	 *
	 * @param fallback indicates whether every ranking reply is invalid
	 * @throws IOException if the fixture cannot be used
	 */
	// Requirement: R-D3
	@Test(dataProvider = "rankingFailures")
	public void rankingRetriesAndAssigns(boolean fallback) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("ranking-retry")),
				authoring().round(new Round(Map.of("safety", 0), true, Map.of(), List.of())).build());
			List<PromptAuthorApproach> ranked = List.of(PromptAuthorApproach.values()).reversed();
			AtomicInteger rankings = new AtomicInteger();
			harness.rankings(_ ->
			{
				int attempt = rankings.incrementAndGet();
				if (fallback || attempt == 1)
					return harness.completed("{}", DriverHarness.JUDGE_MODEL);
				return harness.ranked(ranked, false);
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, () -> harness.promptAuthor().briefs().size() == 3);
				List<PromptAuthorApproach> expected = ranked.subList(0, 3);
				String source = "ranking";
				int expectedRankings = 2;
				if (fallback)
				{
					expected = List.of(PromptAuthorApproach.values()).subList(0, 3);
					source = "fallback";
					expectedRankings = Driver.TRIAGE_ATTEMPTS;
				}
				requireThat(harness.promptAuthor().briefs().stream().map(PromptAuthorBrief::approach).toList(), "approaches").
					isEqualTo(expected);
				requireThat(rankings.get(), "rankingAttempts").isEqualTo(expectedRankings);
				List<SessionRequest> requests = harness.sessions().sessions().stream().map(FakeSession::request).
					filter(request -> request.role() == SessionRole.APPROACH_RANKER).toList();
				for (SessionRequest request : requests)
					requireThat(request, "unchangedRankingBrief").isEqualTo(requests.getFirst());
				JsonNode attempts = StatusReport.of(campaign, ReportEvidence.NONE).toJson(JsonMapper.builder().build()).
					get("prompt_author_attempts");
				for (int index = 0; index < 3; ++index)
				{
					requireThat(attempts.get(index).get("approach").asString(), "reportedApproach").
						isEqualTo(EnumNames.lowerCase(expected.get(index)));
					requireThat(attempts.get(index).get("approach_order_source").asString(), "reportedSource").isEqualTo(source);
				}
			}
		}
	}

	/**
	 * Supplies successful retry and exhausted retry cases.
	 *
	 * @return ranking failure modes
	 */
	@DataProvider
	public Object[][] rankingFailures()
	{
		return new Object[][] {{false}, {true}};
	}

	/**
	 * A saved version-43 campaign keeps its completed rounds and nine-approach rotation, then ranks its next round.
	 *
	 * @throws IOException if campaign storage cannot be used
	 */
	// Requirement: R-D3
	// Requirement: R-D14
	@Test
	public void version43ContinuesIntoRankedRound() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("ranking-migration"));
			JsonMapper json = JsonMapper.builder().build();
			List<AttemptResult> results = new ArrayList<>();
			for (int number = 1; number <= 9; ++number)
			{
				int round = ((number - 1) / 3) * 3 + 1;
				results.add(new AttemptResult(round, Builder.snapshot("candidate-1"), number, "safety",
					Builder.snapshot("candidate-1"), List.of(), 0, false, false));
			}
			Campaign original = authoring().promptAuthorAttempts(9).
				optimization(new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(), results, Map.of())).build();
			Path campaigns = directory.resolve("campaigns");
			FileCampaignStore store = new FileCampaignStore(campaigns, json, Yielding.fileReplacer());
			store.replaceSnapshot(original);
			Path snapshot = FileCampaignStore.heldOutDirectory(campaigns, original.header().id()).resolve("campaign.json");
			ObjectNode saved = (ObjectNode) json.readTree(Files.readString(snapshot));
			saved.put("format_version", 43);
			((ObjectNode) saved.get("optimization")).remove("approaches");
			ObjectNode round = (ObjectNode) saved.get("development").get("round");
			round.remove("rank_approaches");
			round.remove("rotation_size");
			round.remove("approach_orders");
			Files.writeString(snapshot, json.writeValueAsString(saved));
			Campaign loaded = store.load(original.header().id()).orElseThrow();
			requireThat(loaded.optimization().attempts(), "completedRounds").isEqualTo(results);
			requireThat(loaded.optimization().promptAuthorAttempts("safety", 10), "oldAttemptCount").isEqualTo(9);
			store.replaceSnapshot(loaded);
			loaded = store.load(original.header().id()).orElseThrow();
			DriverHarness harness = new DriverHarness(directory.resolve("driver"), loaded);
			harness.trials(_ -> harness.failed());
			harness.rankings(_ -> harness.ranked(List.of(PromptAuthorApproach.values()).reversed(), false));
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> harness.promptAuthor().briefs().size() == 3);
				requireThat(harness.promptAuthor().briefs().stream().map(PromptAuthorBrief::approach).toList(), "continued").
					isEqualTo(List.of(PromptAuthorApproach.CORRECT_INSTRUCTION, PromptAuthorApproach.REWRITE_INSTRUCTIONS,
						PromptAuthorApproach.FIX_LOAD_TRIGGER));
				requireThat(harness.sessions().sessions().stream().filter(session ->
					session.request().role() == SessionRole.APPROACH_RANKER).count(), "rankedNewRoundOnly").isEqualTo(1L);
			}
		}
	}

	/**
	 * A saved version-44 campaign whose attempt had its regression replays planned and judged under the earlier rule
	 * continues under the pooled regression check: an earlier attempt keeps its recorded rejection, and the attempt in
	 * progress, which the earlier rule rejected for one regression failure, receives paired replays and is promoted.
	 *
	 * @throws IOException if campaign storage cannot be used
	 */
	// Requirement: R-D3
	@Test
	public void version44ContinuesUnderPooledCheck() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("pooled-migration"));
			JsonMapper json = JsonMapper.builder().build();
			DrawnTestCase logs = Builder.targeted("safety", "logs");
			DrawnTestCase formal = Builder.targeted("formatting", "formal");
			Sha256 working = Builder.snapshot("candidate-1");
			Sha256 earlier = Builder.snapshot("earlier");
			List<PromotionReplay> earlierReplays = List.of(
				new PromotionReplay(PromotionReplay.Role.TRIGGERING, logs, new TrialKey(Purpose.PROMOTION, 1, earlier,
					"safety", 1), Verdict.PASS),
				new PromotionReplay(PromotionReplay.Role.TRIGGERING, logs, new TrialKey(Purpose.PROMOTION, 1, earlier,
					"safety", 2), Verdict.PASS),
				new PromotionReplay(PromotionReplay.Role.REGRESSION, formal, new TrialKey(Purpose.PROMOTION, 1, earlier,
					"formatting", 1), Verdict.FAIL));
			AttemptResult rejected = new AttemptResult(1, working, 1, "safety", earlier, earlierReplays, 0, false, false);
			DriverHarness harness = new DriverHarness(directory.resolve("driver"), new Builder().confirmed().working(1).
				readyBaseline().build());
			Overlay edited = harness.candidate("edited");
			harness.overlays().save(edited);
			Sha256 snapshot = edited.identity();
			List<PromotionReplay> replays = List.of(
				new PromotionReplay(PromotionReplay.Role.TRIGGERING, logs, new TrialKey(Purpose.PROMOTION, 2, snapshot,
					"safety", 1), Verdict.PASS),
				new PromotionReplay(PromotionReplay.Role.TRIGGERING, logs, new TrialKey(Purpose.PROMOTION, 2, snapshot,
					"safety", 2), Verdict.PASS),
				new PromotionReplay(PromotionReplay.Role.REGRESSION, formal, new TrialKey(Purpose.PROMOTION, 2, snapshot,
					"formatting", 1), Verdict.FAIL));
			List<DiagnosticRerun> failed = List.of(new DiagnosticRerun(Verdict.FAIL, "fail"),
				new DiagnosticRerun(Verdict.FAIL, "fail"), new DiagnosticRerun(Verdict.FAIL, "fail"));
			Builder builder = new Builder();
			for (PromotionReplay replay : replays)
				builder.adopted(replay.trial());
			Campaign original = builder.confirmed().working(1).readyBaseline().
				failure(new Failure(logs, "failure", List.of(), null, failed, List.of(), Builder.FILES)).
				regression(logs, Verdict.FAIL).regression(formal, Verdict.PASS).
				round(new Round(Map.of("safety", 1))).
				promptAuthor(new PromptAuthorAttempt(2, "safety", snapshot, replays, Minimality.start(List.of(), snapshot))).
				optimization(new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(), List.of(rejected),
					Map.of())).build();
			Path campaigns = directory.resolve("campaigns");
			FileCampaignStore store = new FileCampaignStore(campaigns, json, Yielding.fileReplacer());
			store.replaceSnapshot(original);
			Path saved = FileCampaignStore.heldOutDirectory(campaigns, original.header().id()).resolve("campaign.json");
			ObjectNode root = (ObjectNode) json.readTree(Files.readString(saved));
			root.put("format_version", 44);
			Files.writeString(saved, json.writeValueAsString(root));
			Campaign loaded = store.load(original.header().id()).orElseThrow();
			requireThat(loaded.development().promptAuthor(), "attemptInProgress").
				isEqualTo(original.development().promptAuthor());

			harness.store().replaceSnapshot(loaded);
			harness.trials(_ -> harness.passed());
			try (Driver driver = harness.open())
			{
				Campaign promoted = harness.runUntil(driver, state -> state.development().working().number() == 2);
				requireThat(promoted.development().working().snapshot(), "promoted").isEqualTo(snapshot);
				requireThat(promoted.optimization().attempts().getFirst(), "recordedRejection").isEqualTo(rejected);
				AttemptResult continued = promoted.optimization().attempts().getLast();
				requireThat(continued.accepted(), "accepted").isTrue();
				requireThat(continued.replays().stream().filter(replay ->
					replay.role() == PromotionReplay.Role.PAIRED_REGRESSION).count(), "pairedAttemptReplays").isEqualTo(15L);
				requireThat(continued.replays().stream().filter(replay ->
					replay.role() == PromotionReplay.Role.PAIRED_WORKING).count(), "pairedWorkingReplays").isEqualTo(15L);
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
				requireThat(result.optimization().removed(), "removed").isEqualTo(List.of(DriverHarness.PROMPT + "#2"));
				JsonNode attempts = StatusReport.of(result, ReportEvidence.NONE).toJson(JsonMapper.builder().build()).
					get("prompt_author_attempts");
				requireThat(attempts.get(0).get("accepted").asBoolean(), "accepted").isTrue();
				requireThat(attempts.get(0).get("passing_replays").asInt(), "passingReplays").isEqualTo(3);
				requireThat(attempts.get(0).get("total_replays").asInt(), "totalReplays").isEqualTo(5);
			}
		}
	}

	/**
	 * A driver restart retains two passing outcomes and reruns the assigned third promotion replay under the same key.
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
					state.development().promptAuthor().replays().size() == 3 &&
					state.development().promptAuthor().replays().getLast().trial() != null);
				extension = saved.development().promptAuthor().replays().getLast().trial();
				requireThat(extension.purpose(), "purpose").isEqualTo(Purpose.PROMOTION);
				requireThat(saved.development().promptAuthor().replays().stream().filter(replay ->
					replay.verdict() == Verdict.PASS). count(), "retainedPasses").isEqualTo(2);
			}

			try (Driver driver = harness.open())
			{
				driver.step();
				Campaign resumed = harness.campaign();
				requireThat(resumed.trials().stream().map(LogicalTrial::key).toList(), "resumedKey").contains(extension);
				requireThat(resumed.development().count("safety"), "unchangedCounts").isEqualTo(Builder.counts(60, 1));
				Campaign accepted = harness.runUntil(driver, state -> state.development().promptAuthor() != null &&
					!state.development().promptAuthor().minimality().acceptedReplays().isEmpty());
				List<PromotionReplay> replays = accepted.development().promptAuthor().minimality().acceptedReplays();
				requireThat(replays.size(), "acceptedStreak").isEqualTo(3);
				requireThat(replays.getLast().trial(), "completedExtension").isEqualTo(extension);
				requireThat(replays.getLast().verdict(), "extensionVerdict").isEqualTo(Verdict.PASS);
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
	 * <p>
	 * Development counts start near the checkpoint, while scripted failed trials run multiple real rounds through
	 * adoption and stopping. Seeding the checkpoint itself would omit the round transitions asserted here; the
	 * harness advances its manual clock instead of waiting for trial sessions.
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
	 * Verifies that promotion removes an unneeded hunk and keeps replays outside the evidence counts.
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
				requireThat(campaign.optimization().removed(), "removed").isEqualTo(List.of(DriverHarness.PROMPT + "#2"));
				StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
				requireThat(report.toJson(JsonMapper.builder().build()).path("removed_hunks").get(0).asString(),
					"reportedRemoval").isEqualTo(DriverHarness.PROMPT + "#2");
				requireThat(report.summary(ZoneOffset.UTC), "summary").contains(DriverHarness.PROMPT + "#2");
			}
		}
	}

	/**
	 * A single necessary edit reaches its minimal candidate without testing the unchanged working snapshot.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void skipsWorkingSnapshotReduction() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = workingReductionHarness(cleanup.add(Files.createTempDirectory("minimality")));
			Sha256 working = harness.campaign().development().working().snapshot();
			harness.promptAuthors(request -> harness.edit(request, DriverHarness.PROMPT, "needed"));
			harness.trials(request -> harness.passed());

			try (Driver driver = harness.open())
			{
				Campaign promoted = harness.runUntil(driver, state -> state.development().working().number() == 2 ||
					state.trials().stream().anyMatch(trial -> trial.key().purpose() == Purpose.PROMOTION &&
						trial.key().snapshot().equals(working)));
				requireThat(promoted.trials().stream().filter(trial -> trial.key().purpose() == Purpose.PROMOTION &&
					trial.key().snapshot().equals(working)).toList(), "unchangedPromotionTrials").isEmpty();
				requireThat(promoted.development().working().number(), "candidateNumber").isEqualTo(2);
				requireThat(promoted.development().working().snapshot(), "minimalSnapshot").
					isEqualTo(harness.candidate("needed").identity());
				requireThat(promoted.optimization().removed(), "removed").isEmpty();
			}
		}
	}

	/**
	 * A saved reduction equal to the working snapshot settles on restart despite recorded checks and an extension.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void resumesWorkingSnapshotReduction() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = workingReductionHarness(cleanup.add(Files.createTempDirectory("minimality")));
			Campaign initial = harness.campaign();
			Sha256 working = initial.development().working().snapshot();
			Overlay accepted = harness.candidate("needed");
			harness.overlays().save(accepted);
			DrawnTestCase testCase = Builder.targeted("safety", "cache");
			List<PromotionReplay> acceptedReplays = new ArrayList<>();
			for (int sequence = 1; sequence <= 4; ++sequence)
			{
				acceptedReplays.add(new PromotionReplay(PromotionReplay.Role.TRIGGERING, testCase,
					new TrialKey(Purpose.PROMOTION, 1, accepted.identity(), "safety", sequence), Verdict.PASS));
			}
			List<PromotionReplay> stuckReplays = new ArrayList<>();
			for (int sequence = 1; sequence <= 315; ++sequence)
			{
				stuckReplays.add(new PromotionReplay(PromotionReplay.Role.TRIGGERING, testCase,
					new TrialKey(Purpose.PROMOTION, 1, working, "safety", sequence), Verdict.PASS));
			}
			stuckReplays.add(new PromotionReplay(PromotionReplay.Role.TRIGGERING, testCase, null, null));
			List<DiagnosticRerun> workingPasses = new ArrayList<>();
			for (int sequence = 1; sequence <= 332; ++sequence)
				workingPasses.add(new DiagnosticRerun(Verdict.PASS, "working pass"));
			FileDelta file = new FileDelta(DriverHarness.PROMPT, new FileContent("candidate-1".getBytes(UTF_8)),
				new FileContent("needed".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"));
			Minimality search = new Minimality(List.of(file), List.of(DriverHarness.PROMPT + "#1"), 2, 0, List.of(),
				accepted.identity(), acceptedReplays);
			Campaign saved = new Builder().confirmed().working(new Candidate(1, working)).readyBaseline().
				round(new Round(Map.of("safety", 0))).failure(new Failure(testCase, "working failure", List.of(),
					null, workingPasses, List.of(), Builder.FILES)).
				promptAuthor(new PromptAuthorAttempt(1, "safety", working, stuckReplays, search)).build();
			harness.store().replaceSnapshot(saved);

			try (Driver driver = harness.open())
			{
				driver.step();
				Campaign resumed = harness.campaign();
				requireThat(resumed.header().id(), "campaignIdentity").isEqualTo(saved.header().id());
				requireThat(resumed.development().working().number(), "candidateNumber").isEqualTo(2);
				requireThat(resumed.development().working().snapshot(), "minimalSnapshot").
					isEqualTo(accepted.identity());
				requireThat(resumed.development().promptAuthor(), "finishedSearch").isNull();
				requireThat(resumed.trials().stream().filter(trial -> trial.key().purpose() == Purpose.PROMOTION).
					count(), "promotionTrials").isEqualTo(0L);
			}
		}
	}

	/**
	 * Creates a failed working candidate with its actual overlay identity so reductions can compare snapshots.
	 *
	 * @param directory the owned fixture directory
	 * @return the fixture with one triggering failure
	 * @throws IOException if the fixture cannot be used
	 */
	private static DriverHarness workingReductionHarness(Path directory) throws IOException
	{
		DriverHarness harness = new DriverHarness(directory, new Builder().confirmed().working(1).
			readyBaseline().build());
		Sha256 working = harness.overlays().require(Builder.snapshot("candidate-1")).identity();
		harness.overlays().save(harness.candidate("candidate-1"));
		harness.store().replaceSnapshot(new Builder().confirmed().working(new Candidate(1, working)).
			readyBaseline().round(new Round(Map.of("safety", 0))).
			failure(Builder.targeted("safety", "cache")).build());
		return harness;
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
					contains("Uncited changed hunk: " + DriverHarness.PROMPT + "#1");
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
			requireThat(saved.development().promptAuthor().minimality().acceptedReplays(), "passingEvidence").isNotEmpty();
			try (Driver driver = harness.open())
			{
				Campaign resumed = harness.runUntil(driver, state -> state.development().working().number() == 2);
				Write entry = (Write) harness.overlays().require(resumed.development().working().snapshot()).
					entries().get(DriverHarness.PROMPT);
				requireThat(new String(entry.content().bytes(), UTF_8), "prompt").isEqualTo("needed\nseparator\nlast\n");
				requireThat(resumed.optimization().removed(), "removed").isEqualTo(List.of(DriverHarness.PROMPT + "#2"));
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
				String failureId = harness.promptAuthor().briefs().getLast().developmentId(0);
				String response = """
					{"needed_edits":[],"kinds":[{"name":"missing instruction","failures":["%1$s"],
					 "classification":"guidance","hunks":["h1"]}],
					 "hunks":[{"id":"h1","path":"%2$s","before_start":0,"before_end":1,"after_start":0,"after_end":1,
					 "failures":["unknown"],"analogous":"related case","dissimilar":"plain_list"}],"conflicts":[]}
					""".formatted(failureId, DriverHarness.PROMPT);
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
					isEqualTo(List.of("Unknown failure unknown cited at " + DriverHarness.PROMPT));
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
				String failureId = harness.promptAuthor().briefs().getLast().developmentId(0);
				String response = """
					{"needed_edits":[],"kinds":[{"name":"skipped step","failures":["%1$s"],
					 "classification":"guidance","hunks":[]}],
					 "hunks":[{"id":"h1","path":"%2$s","before_start":0,"before_end":1,"after_start":0,"after_end":1,
					 "failures":["%1$s"],"analogous":"related case","dissimilar":"plain_list"}],"conflicts":[]}
					""".formatted(failureId, DriverHarness.PROMPT);
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
	 * Rejects an edited prompt without dissimilar evidence, retains its diagnosis and offers the next prompt author.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void missingDissimilarRetainedAndRetried() throws IOException
	{
		// Requirement: R-D17
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("manifest"));
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork");
			FileInventory fork = InventoryScanner.scan(base, Set.of());
			DriverHarness harness = new DriverHarness(root.resolve("driver"), new Builder().confirmed().working(1).
				readyBaseline().round(new Round(Map.of("safety", 0))).failure(Builder.targeted("safety", "cache")).build(),
				fork, base);
			harness.promptAuthors(request ->
			{
				SessionResult.Completed edited = (SessionResult.Completed) harness.edit(request, DriverHarness.PROMPT);
				String failureId = harness.promptAuthor().briefs().getLast().developmentId(0);
				String response = """
					{"needed_edits":[],"kinds":[{"name":"missing instruction","failures":["%1$s"],
					 "classification":"guidance","hunks":["h1"]}],
					 "hunks":[{"id":"h1","path":"%2$s","before_start":0,"before_end":1,"after_start":0,
					 "after_end":1,"failures":["%1$s"],"analogous":"related change","dissimilar":null}],
					 "conflicts":[]}
					""".formatted(failureId, DriverHarness.PROMPT);
				try
				{
					Files.createDirectories(edited.transcript().getParent());
					Files.writeString(edited.transcript(), response, UTF_8);
				}
				catch (IOException e)
				{
					throw new UncheckedIOException(e);
				}
				return new SessionResult.Completed(edited.transcript(), List.of(List.of(response)), edited.effects(),
					edited.initialProject(), edited.model(), edited.reasoning(), edited.usage(), edited.windows(),
					edited.promptSources(), edited.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> harness.promptAuthor().briefs().size() == 2);
				requireThat(harness.campaign().candidates(), "candidates").isEqualTo(1);
				requireThat(harness.campaign().development().working().number(), "working").isEqualTo(1);
				PromptAuthorDiagnosis retained = harness.campaign().optimization().promptAuthors().getFirst();
				requireThat(retained.rejections(), "rejections").isEqualTo(List.of(
					"Hunk at CLAUDE.md (working lines 0..1, prompt author lines 0..1) has no dissimilar case. " +
						"Name the id of one of the confirmed contract's tests: [formatting, plain_list, safety]"));
				requireThat(Files.isRegularFile(retained.transcript()), "transcript").isTrue();
				requireThat(Files.isDirectory(retained.effects()), "effects").isTrue();
				requireThat(Files.isRegularFile(retained.effects().resolve(DriverHarness.PROMPT)), "editedPrompt").
					isTrue();
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
				String failureId = harness.promptAuthor().briefs().getLast().developmentId(0);
				String response = """
					{"needed_edits":[],"kinds":[{"name":"judge outage","failures":["%1$s"],
					 "classification":"not_guidance","hunks":[]}],
					 "hunks":[{"id":"h1","path":"%2$s","before_start":0,"before_end":1,"after_start":0,"after_end":1,
					 "failures":["%1$s"],"analogous":"related case","dissimilar":"plain_list"}],"conflicts":[]}
					""".formatted(failureId, DriverHarness.PROMPT);
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
	 * @param references counts the working candidate's reference replays on the regression test case
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
			// The working candidate's reference replay on the regression test case, whether the attempt or its reduction
			// runs it
			if (harness.snapshot(request).equals(working) && DriverHarness.cell(request).equals("formatting"))
				references.incrementAndGet();
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
				harness.runUntil(driver, () -> !harness.sessions().sessions().isEmpty());
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
		Path routing = Path.of("routing.md");
		String eventA = "event-a";
		String eventB = "event-b";
		String hooksText = """
			{"hooks": {"%1$s": [{"hooks": [{"type": "command", "command": "%2$s"}]}],
			  "%3$s": [{"hooks": [{"type": "command", "command": "start.sh"}]}]}}
			""";
		String scriptCommand = "bin/repair.sh";
		hooksText = hooksText.formatted(eventA, scriptCommand, eventB);
		Contract contract = ContractFixtures.withDependents(ContractFixtures.contract(), List.of(
			new Dependent(script, new Dependent.WholeFile(), "Repairs read output."),
			new Dependent(hooks,
				new Dependent.HookRegistration(eventA, scriptCommand), "Runs the repair script."),
			new Dependent(routing, new Dependent.Span("Read the target first.\n"),
				"Routes the agent to the target.")));
		Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r--r--");
		Map<Path, String> files = Map.of(DriverHarness.PROMPT, "fork", script, "#!/bin/sh\n", hooks, hooksText,
			routing, "Read the target first.\nBe brief.\n");
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
				requireThat(new String(harness.workspace().readFile(control, routing).orElseThrow().bytes(), UTF_8),
					"routing").isEqualTo("Be brief.\n");
				JsonNode hookConfiguration = JsonMapper.builder().build().readTree(
					harness.workspace().readFile(control, hooks).orElseThrow().bytes()).get("hooks");
				requireThat(hookConfiguration.has(eventA), eventA).isFalse();
				requireThat(hookConfiguration.has(eventB), eventB).isTrue();
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
	 * @throws ScenarioFormatException if a delivered scenario cannot be parsed
	 */
	@Test
	public void certify() throws IOException, ScenarioFormatException
	{
		// Requirement: R-E1
		Contract contract = ContractFixtures.contract();
		Builder start = new Builder().confirmed().working(1).readyBaseline();
		for (String cell : contract.cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
		DecisionBoundaries boundaries = Boundaries.paired(contract.cellIds().size());
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
				requireThat(harness.deliveredRecords().size(), "deliveries").isEqualTo(1);
				CertificationAttempt certified = campaign.attempt(campaign.delivery().attempt());
				requireThat(trialSnapshots, "trialSnapshots").contains(certified.control());
				CertificationRecord record = harness.deliveredRecords().getFirst();
				requireThat(record.certificationIndex(), "certificationIndex").isEqualTo(certified.index());
				// Requirement: R-W11
				requirePromptTestsDelivered(harness, campaign);
				requireThat(record.identity(), "identity").isEqualTo(certified.identity());
				requireThat(result.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Delivered.class);
				requireThat(result.status().status(), "status").isEqualTo(new ReportStatus.Ended(Outcome.CERTIFIED));
				// Requirement: R-M7
				CertifiedEnvironment environment = CertifiedEnvironment.of(record,
					result.delivery().applicability().unchecked());
				requireThat(result.delivery().environment(), "deliveryEnvironment").isEqualTo(environment);
				requireThat(result.status().summary(ZoneOffset.UTC), "summary").
					contains(String.join("\n", environment.lines()));
			}
		}
	}

	/**
	 * Certification waits for the incumbent comparisons: each cell starts with its minimum-pass-rate part certified
	 * and its control and incumbent comparisons one tied pair short of passing, and the attempt certifies only after
	 * an incumbent trial of every cell, which stages the incumbent, passes each incumbent comparison.
	 * <p>
	 * The fixture seeds all earlier evidence instead of replaying the trials that produced it. Both cells must remain:
	 * the incumbent-cell assertion checks that neither is omitted, and the delivered record must contain passing
	 * incumbent comparisons for each assertion. The driver therefore processes the last formal, control and incumbent
	 * trials of each cell and persists their results before delivery; removing a cell or pre-certifying its incumbent
	 * comparisons would lose those assertions' coverage.
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
		DecisionBoundaries boundaries = Boundaries.paired(contract.cellIds().size());
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
				CertificationRecord record = harness.deliveredRecords().getFirst();
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
				CertificationRecord record = harness.deliveredRecords().getFirst();
				for (CertificationRecord.CellResult cell : record.cells())
				{
					requireThat(cell.controlComparisons(), cell.cell() + ".controlComparisons").isEmpty();
					requireThat(cell.incumbentComparisons(), cell.cell() + ".incumbentComparisons").isEmpty();
				}
			}
		}
	}

	/**
	 * A certified campaign whose user kept the incumbent delivers every confirmed prompt test as scenario, values and
	 * workspace files, keeps the base prompt unchanged and appends an {@code incumbent kept} entry, not a certification
	 * record, that names the incumbent by hash and holds the campaign's conditions and nothing proven.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws ScenarioFormatException if a delivered scenario cannot be parsed
	 */
	@Test
	public void keptIncumbentDeliversPromptTests() throws IOException, ScenarioFormatException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("terminal-delivery"));
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork");
			FileInventory fork = InventoryScanner.scan(base, Set.of());
			DriverHarness harness = new DriverHarness(root.resolve("driver"),
				new Builder().confirmed().working(1).lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).build(),
				fork, base);
			DriverContext context = deliveryContext(harness);
			try (Driver driver = Driver.open(context, DriverHarness.CAMPAIGN).orElseThrow())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Delivered.class);
			}
			Campaign campaign = harness.campaign();
			DeliveredTestContract contract = new DeliveredTestContract(base, JsonMapper.builder().build());
			for (DeliveredTest test : DeliveredTest.of(campaign.contract(), campaign.developmentTestCases(),
				DriverHarness.PROMPT))
				contract.delivered(test);
			requireThat(Files.readString(base.resolve(DriverHarness.PROMPT)), "keptPrompt").isEqualTo("fork");
			CertificationHistory history = harness.deliveredHistory();
			requireThat(history.records(), "records").isEmpty();
			requireThat(history.entries().size(), "entries").isEqualTo(1);
			DeliveryEntry entry = (DeliveryEntry) history.entries().getFirst();
			requireThat(entry.label(), "label").isEqualTo(DeliveryLabel.INCUMBENT_KEPT);
			requireThat(entry.provenBetter(), "provenBetter").isEmpty();
			requireThat(entry.incumbent(), "incumbent").isEqualTo(campaign.incumbent().snapshot());
			requireThat(entry.identity(), "identity").isEqualTo(campaign.identity());
			requireThat(entry.promptFiles().get(DriverHarness.PROMPT), "promptFile").
				isEqualTo(Sha256.of("fork".getBytes(UTF_8)));
		}
	}

	/**
	 * A candidate that the user explicitly accepted without certification is delivered with a {@code not certified}
	 * entry, not a certification record: the entry names no attempt, proves nothing, names the incumbent by hash and
	 * holds the campaign's conditions and the delivered prompt file's hash.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws ScenarioFormatException if a delivered scenario cannot be parsed
	 */
	@Test
	public void acceptedUncertifiedAppendsEntry() throws IOException, ScenarioFormatException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("accepted-delivery"));
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork");
			FileInventory fork = InventoryScanner.scan(base, Set.of());
			DriverHarness harness = new DriverHarness(root.resolve("driver"),
				new Builder().confirmed().working(1).build(), fork, base);
			Overlay selected = harness.candidate("accepted");
			Candidate candidate = new Candidate(1, selected.identity());
			harness.overlays().put(candidate.snapshot(), selected);
			harness.workspace().materialize(selected);
			harness.store().replaceSnapshot(new Builder().confirmed().working(candidate).lifecycle(Lifecycle.FINISHED).
				outcome(Outcome.NOT_CERTIFIED).delivery(new DeliveryRequest(candidate, 0, Outcome.NOT_CERTIFIED)).
				build());
			try (Driver driver = Driver.open(deliveryContext(harness), DriverHarness.CAMPAIGN).orElseThrow())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Delivered.class);
				requireThat(result.delivery().environment(), "environment").isNull();
			}
			Campaign campaign = harness.campaign();
			requireThat(Files.readString(base.resolve(DriverHarness.PROMPT)), "deliveredPrompt").isEqualTo("accepted");
			requirePromptTestsDelivered(harness, campaign);
			CertificationHistory history = harness.deliveredHistory();
			requireThat(history.records(), "records").isEmpty();
			requireThat(history.entries().size(), "entries").isEqualTo(1);
			DeliveryEntry entry = (DeliveryEntry) history.entries().getFirst();
			requireThat(entry.label(), "label").isEqualTo(DeliveryLabel.NOT_CERTIFIED);
			requireThat(entry.attempt(), "attempt").isEqualTo(0);
			requireThat(entry.provenBetter(), "provenBetter").isEmpty();
			requireThat(entry.claim(), "claim").isEqualTo("nothing proven");
			requireThat(entry.incumbent(), "incumbent").isEqualTo(campaign.incumbent().snapshot());
			requireThat(entry.identity(), "identity").isEqualTo(campaign.identity());
			requireThat(entry.promptFiles().get(DriverHarness.PROMPT), "promptFile").
				isEqualTo(Sha256.of("accepted".getBytes(UTF_8)));
		}
	}

	/**
	 * Returns endings that have no certified or explicitly selected candidate delivery.
	 *
	 * @return the lifecycle and outcome of each ending
	 */
	@DataProvider
	public static Object[][] uncertifiedEndings()
	{
		return new Object[][]
			{
				{Lifecycle.FINISHED, Outcome.BELOW_MINIMUM},
				{Lifecycle.FINISHED, Outcome.BELOW_CONTROL},
				{Lifecycle.FINISHED, Outcome.BELOW_INCUMBENT},
				{Lifecycle.FINISHED, Outcome.INCONCLUSIVE},
				{Lifecycle.FINISHED, Outcome.STOPPED_FOR_CONTRACT_REVISION},
				{Lifecycle.FINISHED, Outcome.STOPPED_FOR_TEST_REVISION},
				{Lifecycle.FINISHED, Outcome.TESTING_ENVIRONMENT_CHANGED},
				{Lifecycle.FINISHED, Outcome.UNSUPPORTED_INSTRUCTION_SOURCE},
				{Lifecycle.FINISHED, Outcome.INELIGIBLE_TRIAL_EFFECT},
				{Lifecycle.FINISHED, Outcome.UNSUPPORTED_OUTPUT},
				{Lifecycle.FINISHED, Outcome.UNSUPPORTED_TEST_INTERACTION},
				{Lifecycle.FINISHED, Outcome.INVALID_STATISTICAL_ASSUMPTIONS},
				{Lifecycle.CANCELLED, null}
			};
	}

	/**
	 * A campaign that ends without an approved delivery leaves its entire base inventory unchanged and delivers
	 * no prompt tests. The controlled cases cover failure, revision and unavailable outcomes, plus cancellation.
	 *
	 * @param lifecycle the finished or cancelled lifecycle
	 * @param outcome the uncertified outcome, or {@code null} for cancellation
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "uncertifiedEndings")
	public void uncertifiedEndingsLeaveBaseUnchanged(Lifecycle lifecycle, Outcome outcome) throws IOException
	{
		// Requirement: R-W11
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("terminal-delivery"));
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork");
			FileInventory fork = InventoryScanner.scan(base, Set.of());
			DriverHarness harness = new DriverHarness(root.resolve("driver"),
				new Builder().confirmed().working(1).lifecycle(lifecycle).outcome(outcome).build(),
				fork, base);
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.delivery(), "delivery").isNull();
			}
			requireThat(InventoryScanner.scan(base, Set.of()).entries(), "unchangedBase").isEqualTo(fork.entries());
		}
	}

	/**
	 * Returns the harness context with actual file delivery. Delivery gets the diff of {@link GitlessForks}, which is
	 * empty because no Git records the base.
	 *
	 * @param harness the fixture whose existing services are borrowed
	 * @return a context that shares the fixture's stores, workspace and lock coordinator
	 */
	private static DriverContext deliveryContext(DriverHarness harness)
	{
		DriverContext original = harness.context();
		Delivery deliverer = new Delivery(original.store(), original.workspace(),
			new GitlessForks(Set.of(), Yielding.fileReplacer()), original.json(), Yielding.fileReplacer());
		return new DriverContext(original.fileLocks(), original.store(), original.sessions(),
			original.workspace(), original.interrupts(), original.clock(), original.overlays(), original.judge(),
			original.triage(),
				original.ranking(), original.promptAuthor(), original.values(), deliverer::deliver, original.calibrator(),
			original.terminator(), original.leftoverSessions(), original.authority(), original.promptSources(),
			original.environment(), original.lockFile(), original.random(), original.settings(), original.json(),
				original.temporaryDirectory());
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
			boundaries.cap()) - 1;
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
		DriverHarness harness = deliveringHarness(cleanup.add(Files.createTempDirectory("driver")), campaign);
		harness.overlays().put(Builder.CONTROL, harness.candidate(""));
		return harness;
	}

	/**
	 * Returns ports for a campaign whose deliveries write a base worktree that holds the fork's prompt file.
	 *
	 * @param root the directory that the test owns
	 * @param campaign the campaign
	 * @return the ports
	 * @throws IOException if the base worktree or the ports cannot be created
	 */
	private static DriverHarness deliveringHarness(Path root, Campaign campaign) throws IOException
	{
		Path base = Files.createDirectory(root.resolve("base"));
		Files.writeString(base.resolve(DriverHarness.PROMPT), "fork", UTF_8);
		return new DriverHarness(root.resolve("driver"), campaign, InventoryScanner.scan(base, Set.of()), base);
	}

	/**
	 * Requires a campaign's delivery to have written every prompt test of its contract.
	 *
	 * @param harness the ports whose base worktree the delivery wrote
	 * @param campaign the delivered campaign
	 * @throws IOException if a delivered file cannot be read
	 * @throws ScenarioFormatException if a delivered scenario cannot be parsed
	 */
	private static void requirePromptTestsDelivered(DriverHarness harness, Campaign campaign)
		throws IOException, ScenarioFormatException
	{
		DeliveredTestContract contract = new DeliveredTestContract(harness.base(), JsonMapper.builder().build());
		List<DeliveredTest> tests = DeliveredTest.of(campaign.contract(), campaign.developmentTestCases(),
			DriverHarness.PROMPT);
		requireThat(tests.stream().map(DeliveredTest::id).toList(), "tests").
			isEqualTo(campaign.contract().tests().stream().map(PromptTest::id).toList());
		for (DeliveredTest test : tests)
			contract.delivered(test);
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
	 * A development failure, a rerun that reproduces it, a prompt author's edit, promotion replays, and a new working
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
			FileInventory authoring = harness.overlays().require(first).snapshot();
			harness.promptAuthors(request ->
			{
				requireThat(harness.workspace().scanAuthoringCopy(request.source()).entries(), "authoringFiles").
					isEqualTo(authoring.entries());
				return harness.edit(request, DriverHarness.PROMPT);
			});
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
				requireThat(harness.workspace().scanWorktree(), "worktree").
					isEqualTo(harness.overlays().require(promoted).snapshot());
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
				Campaign diagnosing = harness.runUntil(driver, state -> state.development().diagnosis() != null);
				Campaign campaign = harness.runUntil(driver, state -> state.development().diagnosis() == null);
				requireThat(campaign.development().count("safety").failures(), "failures").isEqualTo(1);
				requireThat(campaign.development().working().number(), "working").isEqualTo(1);
				// A failure that does not block keeps the window that development reached
				requireThat(ConcurrencyPolicy.window(campaign), "window").
					isGreaterThanOrEqualTo(ConcurrencyPolicy.window(diagnosing));
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
					window(WorkUnit.formal(1, "safety", false), 4).certificationAttempt(1).
					boundaries(Boundaries.reachableAtFourFailures(neutralBoundaries(), 5)).cell("formatting", 100, 0).
					cell("plain_list", 100, 0).cell("safety", 292, 4).build());
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
		return developing(ContractFixtures.contract());
	}

	/**
	 * Returns a builder for a campaign that confirmed a contract, develops candidate 1 and has met its baseline.
	 *
	 * @param contract the confirmed contract
	 * @return the builder
	 */
	private static Builder developing(Contract contract)
	{
		return new Builder().confirmed(contract, 1).working(1).readyBaseline().development("formatting", 50, 0).
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
	 * Supplies the broker responses that allow a ready continuation or a fresh retry.
	 *
	 * @return each status after the broker reports that the original launch owns active work
	 */
	@DataProvider
	public static Object[][] recoveryStatuses()
	{
		return new Object[][]
		{
			// A checkpoint or a retained generation can be verified after the original runner has stopped.
			{RetainedTrialResponse.Status.READY, true},
			{RetainedTrialResponse.Status.UNSAFE, true},
			// The original can be absent, or the slot can have a binding but no issued startup generation.
			{RetainedTrialResponse.Status.ABSENT, false},
			{RetainedTrialResponse.Status.UNSAFE, false}
		};
	}

	/**
	 * Uses the original launch if the broker finds a ready session, or retries only after that launch is settled.
	 *
	 * @param settledStatus the broker's answer after the original launch stops
	 * @param hasGeneration whether the broker had issued a startup generation before it stopped
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(dataProvider = "recoveryStatuses")
	public void resumesTrialAfterBrokerIsReady(RetainedTrialResponse.Status settledStatus, boolean hasGeneration)
		throws IOException
	{
		// Requirement: R-I12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("driver-recovery"));
			DriverHarness harness = new DriverHarness(directory, developing().ceiling(1).build());
			FileCampaignStore store = new FileCampaignStore(directory.resolve("stored"), JsonMapper.builder().build(),
				Yielding.fileReplacer());
			String campaignId = DriverHarness.CAMPAIGN;
			store.replaceSnapshot(harness.campaign());
			RecoverySessions sessions = new RecoverySessions(harness.sessions(), store, campaignId);
			DriverContext context = recoveryContext(harness, store, sessions);
			try (Driver driver = Driver.open(context, campaignId).orElseThrow())
			{
				driver.step();
				requireThat(sessions.launches.size(), "durablyRecordedLaunches").isEqualTo(1);
				SessionRequest request = harness.sessions().sessions().getFirst().request();
				request.turnObserver().orElseThrow().endAfter(1, harness.passed());
			}
			TrialRecovery originalLaunch = sessions.launches.getFirst();
			TrialRecovery observed = store.readTrialRecovery(campaignId, originalLaunch.key(),
				originalLaunch.attemptOrdinal()).orElseThrow();
			requireThat(observed.observerTurn(), "observedTurn").isEqualTo(1);
			try (Driver driver = Driver.open(context, campaignId).orElseThrow())
			{
				driver.step();
				Campaign busy = store.load(campaignId).orElseThrow();
				LogicalTrial running = trials(busy, TrialState.RUNNING).getFirst();
				requireThat(running.key(), "runningKey").isEqualTo(originalLaunch.key());
				requireThat(running.infrastructureAttempts(), "busyAttempts").isEmpty();
				requireThat(sessions.launches, "busyLaunches").isEqualTo(List.of(originalLaunch));
			}

			sessions.status = settledStatus;
			Optional<Identity> observedIdentity = Optional.of(sessions.identity).filter(_ -> hasGeneration);
			sessions.unsafeIdentity = observedIdentity;
			try (Driver driver = Driver.open(context, campaignId).orElseThrow())
			{
				driver.step();
				if (settledStatus == RetainedTrialResponse.Status.READY)
				{
					requireThat(sessions.claims, "claims").isEqualTo(List.of(new RetainedTrialClaim(
						originalLaunch.requestId(), sessions.identity, 1)));
					requireThat(sessions.launches, "launchesAfterResume").isEqualTo(List.of(originalLaunch));
					SessionRequest request = harness.sessions().sessions().getLast().request();
					requireThat(request.turnObserver().orElseThrow().progress().completedTurn(),
						"restoredTurn").isEqualTo(1);
				}
				else
				{
					requireThat(sessions.claims, "claims").isEmpty();
					requireThat(sessions.launches.size(), "freshLaunches").isEqualTo(2);
					TrialRecovery replacement = sessions.launches.getLast();
					requireThat(replacement.key(), "retriedKey").isEqualTo(originalLaunch.key());
					requireThat(replacement.requestId(), "replacementRequest").isNotEqualTo(originalLaunch.requestId());
					requireThat(replacement.attemptOrdinal(), "replacementOrdinal").isEqualTo(2);
					if (settledStatus == RetainedTrialResponse.Status.UNSAFE)
					{
						requireThat(sessions.discards, "discards").isEqualTo(List.of(RetainedTrialRequest.discard(
							originalLaunch.requestId(), observedIdentity)));
					}
					else
						requireThat(sessions.discards, "discards").isEmpty();
				}
			}
		}
	}

	/**
	 * Supplies the completed turns of a ready session that do not fit the observer's recorded progress of one turn.
	 *
	 * @return fewer turns than the observer decided, and more than one turn beyond its last decision
	 */
	@DataProvider
	public static Object[][] mismatchedPrefixes()
	{
		return new Object[][]{{0}, {3}};
	}

	/**
	 * A ready session whose retained prefix does not fit the observer's recorded progress is discarded, and the trial
	 * reruns as the same logical trial with the interrupted attempt counted.
	 *
	 * @param completedTurns the completed turns the broker reports for the retained session
	 * @throws IOException if the fixture cannot be used
	 */
	@Test(dataProvider = "mismatchedPrefixes")
	public void discardsMismatchedReadyPrefix(int completedTurns) throws IOException
	{
		// Requirement: R-I12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("driver-mismatched-prefix"));
			DriverHarness harness = new DriverHarness(directory, developing().ceiling(1).build());
			FileCampaignStore store = new FileCampaignStore(directory.resolve("stored"), JsonMapper.builder().build(),
				Yielding.fileReplacer());
			String campaignId = DriverHarness.CAMPAIGN;
			store.replaceSnapshot(harness.campaign());
			RecoverySessions sessions = new RecoverySessions(harness.sessions(), store, campaignId);
			DriverContext context = recoveryContext(harness, store, sessions);
			try (Driver driver = Driver.open(context, campaignId).orElseThrow())
			{
				driver.step();
				SessionRequest request = harness.sessions().sessions().getFirst().request();
				request.turnObserver().orElseThrow().endAfter(1, harness.passed());
			}
			TrialRecovery original = sessions.launches.getFirst();
			requireThat(store.readTrialRecovery(campaignId, original.key(), original.attemptOrdinal()).orElseThrow().
				observerTurn(), "observedTurn").isEqualTo(1);

			sessions.status = RetainedTrialResponse.Status.READY;
			sessions.readyTurns = completedTurns;
			try (Driver driver = Driver.open(context, campaignId).orElseThrow())
			{
				driver.step();
				requireThat(sessions.claims, "claims").isEmpty();
				requireThat(sessions.discards, "discards").isEqualTo(List.of(RetainedTrialRequest.discard(
					original.requestId(), Optional.of(sessions.identity))));
				requireThat(sessions.launches.size(), "freshLaunches").isEqualTo(2);
				TrialRecovery replacement = sessions.launches.getLast();
				requireThat(replacement.key(), "sameTrial").isEqualTo(original.key());
				requireThat(replacement.requestId(), "replacementRequest").isNotEqualTo(original.requestId());
				requireThat(replacement.attemptOrdinal(), "replacementOrdinal").isEqualTo(2);
				LogicalTrial retry = trials(store.load(campaignId).orElseThrow(), TrialState.RUNNING).getFirst();
				requireThat(retry.key(), "retryKey").isEqualTo(original.key());
				requireThat(retry.infrastructureAttempts().size(), "countedAttempts").isEqualTo(1);
			}
		}
	}

	/**
	 * A native continuation failure waits for the broker and discards the newly claimed generation before a retry.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void discardsUnresumableGuardedContinuation() throws IOException
	{
		// Requirement: R-I12
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("unresumable-continuation"));
			DriverHarness harness = new DriverHarness(directory, developing().ceiling(1).build());
			FileCampaignStore store = new FileCampaignStore(directory.resolve("stored"), JsonMapper.builder().build(),
				Yielding.fileReplacer());
			String campaignId = DriverHarness.CAMPAIGN;
			store.replaceSnapshot(harness.campaign());
			RecoverySessions sessions = new RecoverySessions(harness.sessions(), store, campaignId);
			DriverContext context = recoveryContext(harness, store, sessions);
			try (Driver driver = Driver.open(context, campaignId).orElseThrow())
			{
				driver.step();
			}
			TrialRecovery original = sessions.launches.getFirst();
			sessions.status = RetainedTrialResponse.Status.READY;
			try (Driver driver = Driver.open(context, campaignId).orElseThrow())
			{
				driver.step();
				requireThat(sessions.claims.size(), "originalClaim").isEqualTo(1);
				Identity claimed = new Identity(UUID.randomUUID(), sessions.identity.session());
				sessions.identity = claimed;
				sessions.status = RetainedTrialResponse.Status.BUSY;
				harness.sessions().sessions().getLast().fail(new RetainedTrialUnresumableException(
					"The harness could not continue the session"));
				requireThat(driver.step(), "waitingForBroker").isInstanceOf(Step.Continue.class);
				requireThat(sessions.discards, "discardsWhileBusy").isEmpty();
				requireThat(sessions.launches, "launchesWhileBusy").isEqualTo(List.of(original));

				sessions.status = RetainedTrialResponse.Status.READY;
				sessions.identity = new Identity(claimed.slotGeneration(),
					Optional.of(new HarnessSession("codex", "22222222-2222-4333-8444-555555555555")));
				requireThat(driver.step(), "waitingForExactSession").isInstanceOf(Step.Continue.class);
				requireThat(sessions.discards, "discardsForDifferentSession").isEmpty();
				sessions.identity = claimed;
				driver.step();
				requireThat(sessions.discards, "guardedDiscard").isEqualTo(List.of(
					RetainedTrialRequest.discard(original.requestId(), Optional.of(claimed))));
				requireThat(sessions.claims.size(), "noSecondClaim").isEqualTo(1);
				requireThat(sessions.launches.size(), "launchesAfterDiscard").isEqualTo(2);
				TrialRecovery replacement = sessions.launches.getLast();
				requireThat(replacement.key(), "sameTrial").isEqualTo(original.key());
				requireThat(replacement.requestId(), "replacementRequest").isNotEqualTo(original.requestId());
				requireThat(replacement.attemptOrdinal(), "replacementOrdinal").isEqualTo(2);
				LogicalTrial retry = trials(store.load(campaignId).orElseThrow(), TrialState.RUNNING).getFirst();
				requireThat(retry.infrastructureFailures(), "chargedFailures").isEqualTo(1);
			}
		}
	}

	/**
	 * Uses the harness fixture's test ports with a real campaign store for durable launch recovery.
	 *
	 * @param harness the campaign fixture
	 * @param store persists the campaign
	 * @param sessions scripts the broker's responses
	 * @return the driver's context
	 */
	private static DriverContext recoveryContext(DriverHarness harness, FileCampaignStore store, SessionPort sessions)
	{
		DriverContext original = harness.context(sessions);
		return new DriverContext(original.fileLocks(), store, original.sessions(), original.workspace(),
			original.interrupts(), original.clock(), original.overlays(), original.judge(), original.triage(),
			original.ranking(), original.promptAuthor(), original.values(), original.deliverer(), original.calibrator(),
			original.terminator(), original.leftoverSessions(), original.authority(), original.promptSources(),
			original.environment(), original.lockFile(), original.random(), original.settings(), original.json(),
			original.temporaryDirectory());
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
				developing().window(4).build());
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
	 * A host interrupt during a certification attempt keeps the outcomes of the trials that ended before it, and the
	 * next slice continues the same attempt: it reruns the interrupted trials under their keys and counts each
	 * outcome once.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void hostInterruptResumesAttempt() throws IOException
	{
		// Requirement: R-E10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().ceiling(4).certificationAttempt(1).boundaries(neutralBoundaries()).
					cell("formatting", 10, 0).cell("plain_list", 10, 0).cell("safety", 10, 0).build());
			int before = attemptOutcomes(harness.campaign());
			Set<TrialKey> interrupted;
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> started = harness.runningTrials();
				requireThat(started.size(), "started").isEqualTo(4);
				started.get(0).end(harness.passed());
				started.get(1).end(harness.passed());
				harness.settle(driver);
				requireThat(attemptOutcomes(harness.campaign()), "ended").isEqualTo(before + 2);

				harness.clock().advance(Duration.ofSeconds(10));
				harness.interrupts().interrupt(harness.clock().instant());
				Step step = driver.step();
				requireThat(step, "step").isInstanceOf(Step.Stopped.class);
				requireThat(((Step.Stopped) step).result().status().outcome(), "outcome").isEqualTo("interrupted");
				Campaign campaign = harness.campaign();
				requireThat(trials(campaign, TrialState.RUNNING), "running").isEmpty();
				interrupted = new HashSet<>();
				for (LogicalTrial trial : trials(campaign, TrialState.ASSIGNED))
					interrupted.add(trial.key());
				requireThat(interrupted, "interrupted").isNotEmpty();
				requireThat(attemptOutcomes(campaign), "kept").isEqualTo(before + 2);
			}

			harness.clock().advance(Duration.ofSeconds(1));
			try (Driver driver = harness.open())
			{
				Set<TrialKey> rerun = new HashSet<>();
				int ended = 0;
				for (int iteration = 0; iteration < 10 && !rerun.containsAll(interrupted); ++iteration)
				{
					driver.step();
					for (LogicalTrial trial : trials(harness.campaign(), TrialState.RUNNING))
						rerun.add(trial.key());
					for (FakeSession session : harness.runningTrials())
					{
						session.end(harness.passed());
						++ended;
					}
					harness.settle(driver);
				}
				requireThat(rerun, "rerun").containsAll(interrupted);

				Campaign campaign = harness.campaign();
				CertificationAttempt attempt = campaign.runningAttempt().orElseThrow();
				requireThat(attempt.index(), "index").isEqualTo(1);
				requireThat(attemptOutcomes(campaign), "resumed").isEqualTo(before + 2 + ended);
			}
		}
	}

	/**
	 * Returns the number of outcomes that the running certification attempt counts, summed over its cells.
	 *
	 * @param campaign the campaign
	 * @return the number of outcomes
	 */
	private static int attemptOutcomes(Campaign campaign)
	{
		CertificationAttempt attempt = campaign.runningAttempt().orElseThrow();
		int outcomes = 0;
		for (String cell : campaign.contract().cellIds())
			outcomes += attempt.count(cell).outcomes();
		return outcomes;
	}

	/**
	 * A certification attempt reaches its cap without a decision, ends inconclusive, and the campaign returns
	 * to development. The cell can still certify until its last outcome before the cap fails.
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
				developing().certificationAttempt(1).
					boundaries(Boundaries.reachableAtFourFailures(neutralBoundaries(), 6)).cell("formatting", 295, 4).
					cell("plain_list", 100, 0).cell("safety", 100, 0).build());
			try (Driver driver = harness.open())
			{
				driver.step();
				List<FakeSession> trials = harness.runningTrials();
				requireThat(trials.size(), "trials").isEqualTo(1);
				requireThat(DriverHarness.cell(trials.getFirst().request()), "test").isEqualTo("formatting");
				trials.getFirst().end(harness.failed());
				harness.settle(driver);

				Campaign campaign = harness.campaign();
				requireThat(campaign.attempt(1).outcome(), "outcome").isEqualTo(Outcome.INCONCLUSIVE);
				requireThat(campaign.runningAttempt().isEmpty(), "running").isTrue();
				harness.runUntil(driver, state -> !state.trials().isEmpty());
				// The failure that reached the cap is reproduced in development before development continues
				for (LogicalTrial trial : harness.campaign().trials())
				{
					requireThat(Set.of(Purpose.REPRODUCTION, Purpose.DEVELOPMENT), "developmentPurposes").
						contains(trial.key().purpose());
				}
			}
		}
	}

	/**
	 * A campaign stored while its certification attempt kept running although a cell could no longer certify within
	 * its cap ends the attempt inconclusive once loaded, before any further candidate formal trial runs, and the
	 * status names the cell with its counts.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void loadedOutOfReachAttemptEnds() throws IOException
	{
		// Requirement: R-F2
		// With 4 failures, 297 passes would be needed, beyond the 296 passes left within the cap of 300
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().certificationAttempt(1).boundaries(neutralBoundaries()).cell("formatting", 120, 4).
					cell("plain_list", 100, 0).cell("safety", 100, 0).build());
			Set<Purpose> purposes = ConcurrentHashMap.newKeySet();
			harness.trials(request ->
			{
				purposes.add(runningTrial(harness, request).key().purpose());
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver, state -> !state.attempt(1).isRunning());
				CertificationAttempt attempt = campaign.attempt(1);
				requireThat(attempt.outcome(), "outcome").isEqualTo(Outcome.INCONCLUSIVE);
				requireThat(attempt.count("formatting").passes(), "passes").isEqualTo(120);
				requireThat(attempt.count("formatting").failures(), "failures").isEqualTo(4);
				requireThat(purposes, "purposes").doesNotContain(Purpose.FORMAL);
				requireThat(StatusReport.of(campaign, ReportEvidence.NONE).summary(ZoneOffset.UTC), "summary").
					contains("Attempt 1: not certified: inconclusive (formatting out of reach with 120 passes and 4 " +
						"failures)");
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
				developing().window(2).build());
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
				assertSameTrialRequest(rerun.request(), limitedRequest);
			}
		}
	}

	/**
	 * A completed trial session appends a session record to its trial's record: when the session started and ended,
	 * how many trial sessions were running when it started, the throttle state after its reported rate-limit windows,
	 * those windows, the harness's login, the session's token usage and how its shell commands ran, which the campaign
	 * also counts for the trial's purpose.
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
				developing().window(2).build(), HostLogin.SUBSCRIPTION);
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
				CommandSummary commands = new CommandSummary(Duration.ofSeconds(12), 1);
				harness.sessions().supplyEvidence(passed.transcript(), new SessionEvidence(List.of(), commands));
				driver.step();

				// The 85% window stops the concurrency window from growing beyond the 2 trials that are running
				Throttle frozen = new Throttle(Instant.EPOCH, 2, 0, 2);
				requireThat(recordedSessions(harness), "sessions").isEqualTo(List.of(new TrialSession(
					DriverHarness.START, DriverHarness.START.plus(Duration.ofMinutes(3)), 2, frozen, windows,
					HostLogin.SUBSCRIPTION, usage, commands, null)));
				requireThat(harness.campaign().optimization().commands(), "commands").isEqualTo(Map.of(
					Purpose.DEVELOPMENT, Map.of(0, new CommandCounts(1, 1, 1, 1, 1))));
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
				developing().window(2).build(), HostLogin.API_KEY);
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
					new Throttle(resetsAt, 1, 0, 1), windows, HostLogin.API_KEY, null, CommandSummary.UNKNOWN, null)));
				requireThat(harness.campaign().optimization().commands(), "commands").isEmpty();
			}
		}
	}

	/**
	 * Verifies that each trial session's record holds the authentication that its harness's sessions use, the source
	 * home, the copied parts and the class of login, and that a campaign resumed by a command that names another source
	 * home proceeds: its later sessions record the new source home, while its earlier trial keeps its session record and
	 * every trial keeps the campaign's measurement identity, which the source home is not part of.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void trialSessionsRecordAuthentication() throws IOException
	{
		// Requirement: R-I14
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("trial-authentication")),
				developing().window(2).build(), HostLogin.API_KEY);
			AuthenticationEvidence first = new AuthenticationEvidence(Path.of("/homes/first"), List.of("apiKeyHelper"),
				HostLogin.API_KEY);
			AuthenticationEvidence second = new AuthenticationEvidence(Path.of("/homes/second"),
				List.of("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN"), HostLogin.SUBSCRIPTION);
			harness.sessions().supplyAuthentication(first);
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.runningTrials().getFirst().end(harness.passed());
				driver.step();
			}
			List<TrialSession> before = recordedSessions(harness);
			requireThat(before.size(), "before.size()").isEqualTo(1);
			requireThat(before.getFirst().authentication(), "firstAuthentication").isEqualTo(first);
			LogicalTrial earlier = null;
			for (LogicalTrial trial : harness.campaign().trials())
			{
				if (!harness.crashingStore().trialSessions(trial.key()).isEmpty())
					earlier = trial;
			}
			requireThat(earlier, "earlier").isNotNull();

			harness.sessions().supplyAuthentication(second);
			try (Driver driver = harness.open())
			{
				driver.step();
				harness.runningTrials().getFirst().end(harness.passed());
				driver.step();
			}

			List<TrialSession> after = recordedSessions(harness);
			requireThat(after, "after").contains(before.getFirst());
			requireThat(after.stream().map(TrialSession::authentication).toList(), "authentications").
				contains(second);
			requireThat(harness.crashingStore().trialSessions(earlier.key()).getFirst(), "earlierSession").
				isEqualTo(before.getFirst());
			for (LogicalTrial trial : harness.campaign().trials())
				requireThat(trial.identity(), "identity").isEqualTo(earlier.identity());
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
				developing().window(1).build());
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
				developing().window(1).build());
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
					assertSameTrialRequest(rerun.getFirst().request(), refusedRequest);
				}
			}
		}
	}

	/**
	 * A slice that began before the driver opened, as when the command that opens it first ran work of its own, counts
	 * that work against its budget: once the budget and the grace period have passed since the slice began, the
	 * driver's first step stops the slice without admitting a trial.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void sliceThatBeganEarlierEndsEarlier() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("slice-began-earlier")),
				developing().window(1).build());
			DriverContext context = harness.context();
			Instant began = harness.clock().instant().minus(context.settings().budget()).
				minus(context.settings().grace());
			CampaignLock lock = CampaignLock.tryAcquire(context.fileLocks(), context.lockFile()).orElseThrow();
			try (Driver driver = Driver.open(context, DriverHarness.CAMPAIGN, lock, began))
			{
				requireThat(driver.step(), "step").isInstanceOf(Step.Stopped.class);
				requireThat(harness.runningTrials(), "runningTrials").isEmpty();
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
				developing().window(1).build());
			harness.trials(_ -> new SessionResult.Refused(
				new Hold(harness.clock().instant().plus(Duration.ofMinutes(30)), 6), true, List.of()));
			try (Driver driver = harness.open())
			{
				Header campaignHeader = harness.campaign().header();
				String commandName = campaignHeader.harness().commandName();
				SliceResult result = harness.runSlice(driver);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").
					isEqualTo("The provider refused " + commandName + " requests after a 30-minute hold");
				Campaign campaign = harness.campaign();
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(1);
				List<LogicalTrial> queued = trials(campaign, TrialState.ASSIGNED);
				requireThat(queued.size(), "queued").isEqualTo(1);
				requireThat(queued.getFirst().infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that a trial session that is refused before it starts stops the slice at once with the refusal's
	 * message, verbatim in the status and its summary, without retrying the trial, counting the
	 * refusal as an infrastructure failure, or raising a decision.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void launchRefusalStopsTrialWork() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("launch-refused")),
				developing().window(1).build());
			String refusal = "No request server serves this harness-a session. Start the request server, then run " +
				"the command again.";
			harness.trials(_ -> new SessionResult.LaunchRefused(refusal, List.of()));
			try (Driver driver = harness.open())
			{
				StatusReport report = harness.runSlice(driver).status();
				requireThat(report.outcomeDetail(), "outcomeDetail").isEqualTo(refusal);
				requireThat(report.summary(ZoneOffset.UTC), "summary").contains(refusal);
				requireThat(harness.sessions().sessions().size(), "sessions").isEqualTo(1);
				Campaign campaign = harness.campaign();
				requireThat(campaign.pending(), "pending").isNull();
				List<LogicalTrial> queued = trials(campaign, TrialState.ASSIGNED);
				requireThat(queued.size(), "queued").isEqualTo(1);
				requireThat(queued.getFirst().infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
			}
		}
	}

	/**
	 * Verifies that two trial sessions that fail without an outcome, followed by one that completes, rerun the same
	 * logical trial and continue the campaign without a decision about trial failures.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void infrastructureFailuresBelowLimitRetry() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("failures-below-limit")),
				developing().window(1).build());
			AtomicInteger failures = new AtomicInteger(Campaign.INFRASTRUCTURE_FAILURE_LIMIT - 1);
			harness.trials(_ ->
			{
				if (failures.getAndDecrement() > 0)
					return new SessionResult.Failed("The harness crashed", List.of());
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				// The completed trial reaches its judge
				Campaign campaign = harness.runUntil(driver, () -> harness.sessions().sessions().stream().
					anyMatch(session -> session.request().role() == SessionRole.JUDGE));
				requireThat(campaign.pending(), "pending").isNull();
				List<FakeSession> trials = harness.sessions().sessions().stream().
					filter(session -> session.request().role() == SessionRole.TRIAL).toList();
				requireThat(trials.size(), "trials").isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
				for (FakeSession trial : trials)
					assertSameTrialRequest(trial.request(), trials.getFirst().request());
				requireThat(campaign.trials().getFirst().infrastructureFailures(), "infrastructureFailures").
					isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT - 1);
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
				developing().window(1).build());
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
				harness.runUntil(driver, () -> starts.size() > Driver.PROMPT_AUTHOR_FAILURE_LIMIT);
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
				requireThat(harness.workspace().scanWorktree(), "worktree").
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
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().ceiling(1).
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
				requireThat(result.status().pending(), "pending").isEqualTo(StatusReport.REVISION_PENDING);
			}
			int before = harness.sessions().sessions().size();
			try (Driver driver = harness.open())
			{
				driver.apply(confirm());
				harness.runUntil(driver, () -> harness.sessions().sessions().size() >= before + 10);
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
						CampaignLock.tryAcquire(harness.context().fileLocks(), directory.resolve("unlock.lock"),
							_ -> channel).orElseThrow()).close();
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
	 * A changed staged target refuses the next trial admission and driver cleanup interrupts its running session.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the thread is interrupted
	 */
	@Test
	public void corruptTargetBlocksTrialStart() throws IOException, InterruptedException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver-corrupt-target")),
				developing().window(4).build());
			AtomicBoolean changed = new AtomicBoolean();
			harness.sessions().onStart(session ->
			{
				if (changed.compareAndSet(false, true))
				{
					try
					{
						Files.writeString(session.request().source().resolve(DriverHarness.PROMPT), "changed", UTF_8);
					}
					catch (IOException e)
					{
						throw new UncheckedIOException(e);
					}
				}
			});
			IOException failure = null;

			try (Driver driver = harness.open())
			{
				try
				{
					driver.step();
				}
				catch (IOException e)
				{
					failure = e;
				}
			}

			requireThat(failure, "failure").isInstanceOf(SnapshotMismatchException.class);
				requireThat(failure.getMessage(), "message").contains("snapshot").contains(
					DriverHarness.PROMPT.toString());
			List<FakeSession> sessions = harness.sessions().sessions();
			requireThat(sessions.size(), "sessions").isEqualTo(1);
			requireThat(sessions.getFirst().isInterruptRequested(), "interruptRequested").isTrue();
			requireThat(sessions.stream().map(session -> session.request().role()).toList(), "roles").
				isEqualTo(List.of(SessionRole.TRIAL));
		}
	}

	/**
	 * A certification attempt ends inconclusive with one test proven better and the other certified, once a formal
	 * failure leaves the proven-better test unable to certify within its cap. The campaign pauses with
	 * {@code keep improvement}, {@code optimize further} and {@code stop}; the choice is still pending after a restart,
	 * and keeping the improvement delivers the attempt's candidate labelled {@code improved, not certified}.
	 * <p>
	 * The fixture seeds development, baseline and formal counts one failure before the test is out of reach. The
	 * remaining formal outcome, persisted pending decision, restart and delivery are required to prove the public
	 * choice flow; their filesystem work can exceed one second during a parallel build.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws ScenarioFormatException if a delivered scenario cannot be parsed
	 */
	@Test
	public void keepImprovement() throws IOException, ScenarioFormatException
	{
		// Requirement: R-F6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("driver"));
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork");
			FileInventory fork = InventoryScanner.scan(base, Set.of());
			DriverHarness harness = new DriverHarness(root.resolve("driver"),
				new Builder().confirmed().working(1).build(), fork, base);
			Overlay selected = harness.candidate("kept improvement");
			Candidate candidate = new Candidate(1, selected.identity());
			harness.overlays().put(candidate.snapshot(), selected);
			harness.workspace().materialize(selected);
			harness.store().replaceSnapshot(new Builder().confirmed().working(candidate).readyDevelopment().
				readyBaseline().certificationAttempt(1).candidate(candidate).cell("formatting", 230, 3).
				cell("plain_list", 100, 0).cell("safety", 100, 0).build());
			harness.trials(_ -> harness.failed());
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
			DriverContext context = deliveryContext(harness);
			try (Driver driver = Driver.open(context, DriverHarness.CAMPAIGN).orElseThrow())
			{
				requireThat(harness.campaign().pending(), "pending").
					isInstanceOf(PendingDecision.ImprovementProven.class);
				driver.apply(CampaignEvent.Choose.keep(1));
				SliceResult result = harness.runSlice(driver);
				Campaign campaign = harness.campaign();
				requireThat(campaign.delivery(), "delivery").isEqualTo(new DeliveryRequest(
					candidate, 1, Outcome.IMPROVED_NOT_CERTIFIED));
				requireThat(result.delivery().result(), "deliveryResult").isInstanceOf(DeliveryResult.Delivered.class);
				requireThat(((DeliveryResult.Delivered) result.delivery().result()).label(), "deliveryLabel").
					isEqualTo(Outcome.IMPROVED_NOT_CERTIFIED);
				requireThat(Files.readString(base.resolve(DriverHarness.PROMPT)), "deliveredPrompt").
					isEqualTo("kept improvement");
				requirePromptTestsDelivered(harness, campaign);
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Ended(Outcome.IMPROVED_NOT_CERTIFIED));
				CertificationHistory history = harness.deliveredHistory();
				requireThat(history.records(), "records").isEmpty();
				requireThat(history.entries().size(), "entries").isEqualTo(1);
				DeliveryEntry entry = (DeliveryEntry) history.entries().getFirst();
				requireThat(entry.label(), "label").isEqualTo(DeliveryLabel.IMPROVED_NOT_CERTIFIED);
				requireThat(entry.attempt(), "attempt").isEqualTo(1);
				requireThat(entry.provenBetter(), "provenBetter").
					isEqualTo(List.of("formatting", "plain_list", "safety"));
				requireThat(entry.incumbent(), "incumbent").isEqualTo(campaign.incumbent().snapshot());
				requireThat(entry.identity(), "identity").isEqualTo(campaign.attempt(1).identity());
				requireThat(entry.promptFiles().get(DriverHarness.PROMPT), "promptFile").
					isEqualTo(Sha256.of("kept improvement".getBytes(UTF_8)));
				requireThat(result.delivery().environment(), "environment").isNull();
			}
		}
	}

	/**
	 * An attempt qualifies for keep improvement, the user optimizes further, a later candidate's attempt
	 * also qualifies with a lower bound on its weakest test, and later attempts fail. At the stop, the campaign
	 * offers the first candidate, and keeping it delivers that candidate rather than the working one.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws ScenarioFormatException if a delivered scenario cannot be parsed
	 */
	@Test
	public void bestCandidateSoFar() throws IOException, ScenarioFormatException
	{
		// Requirement: R-F6
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("best-delivery"));
			Path base = Files.createDirectory(root.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork");
			FileInventory fork = InventoryScanner.scan(base, Set.of());
			DriverHarness harness = new DriverHarness(root.resolve("driver"),
				new Builder().confirmed().working(2).build(), fork, base);
			Overlay earlier = harness.candidate("earlier best");
			Overlay later = harness.candidate("later working");
			Candidate first = new Candidate(1, earlier.identity());
			Candidate second = new Candidate(2, later.identity());
			harness.overlays().put(first.snapshot(), earlier);
			harness.overlays().put(second.snapshot(), later);
			harness.workspace().materialize(later);
			harness.store().replaceSnapshot(new Builder().confirmed().working(second).readyDevelopment().
				readyBaseline().certificationAttempt(1).candidate(first).cell("formatting", 296, 4).
				cell("plain_list", 250, 0).cell("safety", 250, 0).outcome(Outcome.INCONCLUSIVE).campaign().
				certificationAttempt(2).candidate(second).cell("formatting", 295, 4).cell("plain_list", 100, 0).
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
			DriverContext context = deliveryContext(harness);
			try (Driver driver = Driver.open(context, DriverHarness.CAMPAIGN).orElseThrow())
			{
				driver.apply(new CampaignEvent.Cancel());
				Campaign campaign = harness.campaign();
				requireThat(campaign.choices(), "choices").contains(Choice.KEEP_IMPROVEMENT);
				StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
				requireThat(report.keepOffers().getFirst().attempt(), "offer").isEqualTo(1);

				driver.apply(CampaignEvent.Choose.keep(1));
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.campaign().delivery(), "delivery").
					isEqualTo(new DeliveryRequest(first, 1, Outcome.IMPROVED_NOT_CERTIFIED));
				requireThat(harness.campaign().development().working(), "working").isEqualTo(second);
				requireThat(result.delivery().result(), "deliveryResult").isInstanceOf(DeliveryResult.Delivered.class);
				requireThat(((DeliveryResult.Delivered) result.delivery().result()).label(), "deliveryLabel").
					isEqualTo(Outcome.IMPROVED_NOT_CERTIFIED);
				requireThat(Files.readString(base.resolve(DriverHarness.PROMPT)), "deliveredPrompt").
					isEqualTo("earlier best");
				// Requirement: R-W11
				Map<Path, FileEntry> delivered = new HashMap<>(InventoryScanner.scan(base, Set.of()).entries());
				Path history = PromptTestLocations.certificationHistory(DriverHarness.PROMPT);
				requireThat(delivered.remove(history), "history").isNotNull();
				// The history's directory exists only to hold it
				for (Path parent = history.getParent(); parent != null; parent = parent.getParent())
					delivered.remove(parent);
				// An improvement that is not certified delivers the prompt tests too
				requirePromptTestsDelivered(harness, campaign);
				for (DeliveredTest test : DeliveredTest.of(campaign.contract(), campaign.developmentTestCases(),
					DriverHarness.PROMPT))
				{
					Path directory = test.location().directory();
					delivered.keySet().removeIf(path -> path.startsWith(directory));
					for (Path parent = directory.getParent(); parent != null; parent = parent.getParent())
						delivered.remove(parent);
				}
				requireThat(delivered, "deliveredInventory").isEqualTo(earlier.snapshot().entries());
			}
		}
	}

	/**
	 * Mid-attempt, a trial session reports a different model. Its record holds no outcome and the campaign pauses.
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
				new Builder().confirmed().working(2).readyDevelopment().readyBaseline().ceiling(1).
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
					isEqualTo(List.of(Choice.RESTORE, Choice.NEW_IDENTITY, Choice.KEEP_IDENTITY, Choice.STOP));
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
			CommitGate.MINIMUM_TRIALS - 1, 0).ceiling(1).build();
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
		// The failing cells stay within reach until their fifth failure makes them clearly below
		DecisionBoundaries boundaries = Boundaries.pairedReachableAtFourFailures(contract.cellIds().size(), 5);
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
		Contract contract = ContractFixtures.contract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("selected-trials"));
			Path pluginBase = pluginBase(directory.resolve("plugin-base"));
			TrialPlugin plugin = builtPlugin(pluginBaseDigest(directory.resolve("plugin-base-identity"), pluginBase));
			Path project = Files.createDirectories(directory.resolve("project"));
			Files.createDirectories(project.resolve("rules"));
			Files.createDirectories(project.resolve(".cat/tests/main"));
			Files.writeString(project.resolve(DriverHarness.PROMPT), "fork", UTF_8);
			Files.writeString(project.resolve("rules/selection.md"), "mapped rule", UTF_8);
			Files.writeString(project.resolve("unrelated.md"), "unrelated", UTF_8);
			Files.writeString(project.resolve(".cat/tests/main/private.md"), "held out", UTF_8);
			FileInventory fork = InventoryScanner.scan(project, Set.of());
			DriverHarness harness = new DriverHarness(directory.resolve("driver"),
				new Builder().confirmed(contract, 1).working(1).plugins(List.of(plugin)).build(), fork, project);
			requireThat(harness.workspace().copyPluginBase(pluginBase), "pluginBaseDigest").
				isEqualTo(plugin.baseDigest());
			List<SessionRequest> trials = new CopyOnWriteArrayList<>();
			harness.trials(request ->
			{
				trials.add(request);
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> trials.size() >= 2);
			}

			requireThat(trials, "trials").isNotEmpty();
			Set<Path> sources = harness.workspace().stagedSources();
			for (SessionRequest trial : trials)
				requireThat(sources, "stagedSources").contains(trial.source());
			for (Path source : sources)
			{
				try (Stream<Path> files = Files.walk(source))
				{
					requireThat(Set.copyOf(files.filter(Files::isRegularFile).map(source::relativize).toList()),
						"sourceFiles").isEqualTo(Set.of(DriverHarness.PROMPT, Path.of("rules/selection.md")));
				}
				requireThat(Files.readString(source.resolve("rules/selection.md"), UTF_8), "mappedRule").
					isEqualTo("mapped rule");
			}
		}
	}

	/**
	 * Each trial's session receives its prompt test's fixtures with the values of the trial's own test case in their
	 * paths and contents, and a trial of a test without fixtures receives none.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void trialsReceiveTheirFixtures() throws IOException
	{
		// Requirement: R-C21
		Contract contract = ContractFixtures.withWorkspaceFiles(ContractFixtures.contract(), "safety",
			List.of(new WorkspaceFile("{{target}}/README.md", "Delete {{target}} only after asking.\n")));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("fixture-trials"));
			DriverHarness harness = new DriverHarness(directory, new Builder().confirmed(contract, 1).working(1).
				build());
			List<SessionRequest> trials = new CopyOnWriteArrayList<>();
			harness.trials(request ->
			{
				trials.add(request);
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> trials.stream().anyMatch(trial -> !trial.files().isEmpty()) &&
					trials.stream().anyMatch(trial -> trial.files().isEmpty()));
			}
			int index = 0;
			for (SessionRequest trial : trials)
			{
				String target = trial.turns().getFirst().replaceAll("^Delete the (.+) directory\\.$", "$1");
				if (target.equals(trial.turns().getFirst()))
				{
					requireThat(trial.files().isEmpty(), "noFixtures").isTrue();
					continue;
				}
				Path workspace = Files.createDirectory(directory.resolve("workspace-" + index));
				++index;
				trial.files().write(workspace);
				requireThat(Files.readString(workspace.resolve(target + "/README.md")), "fixture").
					isEqualTo("Delete " + target + " only after asking.\n");
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
		Contract contract = ContractFixtures.contract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("plugin-trials"));
			Path builtBase = pluginBase(directory.resolve("built-plugin-base"));
			Path installedBase = pluginBase(directory.resolve("installed-plugin-base"));
			TrialPlugin plugin = builtPlugin(pluginBaseDigest(directory.resolve("built-base-identity"), builtBase));
			TrialPlugin installed = new TrialPlugin("tools@market",
				pluginBaseDigest(directory.resolve("installed-base-identity"), installedBase),
				new TrialPlugin.Installed(), List.of(), null);
			DriverHarness harness = new DriverHarness(directory,
				new Builder().confirmed(contract, 1).working(1).plugins(List.of(plugin, installed)).build());
			requireThat(harness.workspace().copyPluginBase(builtBase), "builtPluginBaseDigest").
				isEqualTo(plugin.baseDigest());
			requireThat(harness.workspace().copyPluginBase(installedBase), "installedPluginBaseDigest").
				isEqualTo(installed.baseDigest());
			List<SessionRequest> trials = new CopyOnWriteArrayList<>();
			harness.trials(request ->
			{
				trials.add(request);
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> trials.size() >= 2);
			}
			requireThat(trials, "trials").isNotEmpty();
			for (SessionRequest trial : trials)
			{
				List<Path> expected = List.of(harness.workspace().pluginCopy(plugin, trial.source(), PluginRemovals.NONE),
					harness.workspace().pluginCopy(installed, trial.source(), PluginRemovals.NONE));
				requireThat(trial.plugins(), "plugins").isEqualTo(expected);
				requireThat(trial.pluginInputs().getFirst().installedId(), "builtSource").isEmpty();
				requireThat(trial.pluginInputs().getLast().installedId(), "installedSource").
					isEqualTo(Optional.of("tools@market"));
			}
		}
	}

	/**
	 * Control trials run on the control source and install a copy of a project-built trial plugin without the target's
	 * dependents in the plugin, and every other trial installs the copy with them. Each cell starts one control pair
	 * short of passing.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void controlTrialsRemovePluginDependents() throws IOException
	{
		// Requirement: R-W10
		Contract contract = ContractFixtures.withDependents(ContractFixtures.contract(),
			List.of(new Dependent(Dependent.Location.PLUGIN, "cat", Path.of("bin/read-part"), new Dependent.WholeFile(),
				"Reads the part that the target tells the agent to read.")));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("plugin-control-trials"));
			Path pluginBase = pluginBase(directory.resolve("plugin-base"));
			TrialPlugin plugin = builtPlugin(pluginBaseDigest(directory.resolve("plugin-base-identity"), pluginBase));
			Builder start = new Builder().confirmed(contract, 1).working(1).plugins(List.of(plugin)).readyBaseline();
			for (String cell : contract.cellIds())
				start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
			DecisionBoundaries boundaries = Boundaries.paired(contract.cellIds().size());
			Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
			for (String cell : contract.cellIds())
				oneControlPairShort(running, boundaries, contract, cell);
			Campaign campaign = running.build();
			DriverHarness harness = runningAttemptHarness(cleanup, campaign);
			requireThat(harness.workspace().copyPluginBase(pluginBase), "pluginBaseDigest").
				isEqualTo(plugin.baseDigest());
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
				boolean onControl = harness.snapshot(trial).equals(control);
				if (onControl)
				{
					expected = removals;
					++controlTrials;
				}
				requireThat(trial.controlSource(), "controlSource").isEqualTo(onControl);
				requireThat(trial.plugins(), "plugins").isEqualTo(List.of(
					harness.workspace().pluginCopy(plugin, trial.source(), expected)));
			}
			requireThat(controlTrials, "controlTrials").isPositive();
		}
	}

	/**
	 * Returns a trial plugin that the project builds.
	 *
	 * @param baseDigest the identity of the plugin's copied base
	 * @return the plugin
	 */
	private static TrialPlugin builtPlugin(String baseDigest)
	{
		return new TrialPlugin("cat", baseDigest, new TrialPlugin.Build(List.of("build"), Path.of("out")),
			List.of(new TrialPlugin.Mapping(Path.of("rules"), PLUGIN_RULES)), null);
	}

	/**
	 * Creates the physical base files used by the trial-plugin fixtures.
	 *
	 * @param directory the directory that owns the base
	 * @return the base directory
	 * @throws IOException if a base file cannot be created
	 */
	private static Path pluginBase(Path directory) throws IOException
	{
		Files.createDirectories(directory.resolve(PLUGIN_RULES));
		Files.createDirectories(directory.resolve("bin"));
		Files.writeString(directory.resolve(PLUGIN_RULES.resolve("base.md")), "base rule\n", UTF_8);
		Files.writeString(directory.resolve("bin/read-part"), "read part\n", UTF_8);
		return directory;
	}

	/**
	 * Returns the identity reported by the supported plugin-base copy operation.
	 *
	 * @param storage the storage directory owned by the identity fixture
	 * @param base the physical plugin base
	 * @return the copied base's identity
	 * @throws IOException if the base cannot be copied
	 */
	private static String pluginBaseDigest(Path storage, Path base) throws IOException
	{
		FileInventory empty = new FileInventory(Path.of("/project").toAbsolutePath(), Map.of());
		return new FakeWorkspacePort(storage, empty).copyPluginBase(base);
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
		Builder start = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(TierFixtures.SMALL_HIGH, 200, 0).development(TierFixtures.SAFETY, 200, 0);
		// The failing cell stays within reach until its fifth failure makes it clearly below
		DecisionBoundaries boundaries = Boundaries.reachableAtFourFailures(Boundaries.standard(
			CellTier.scopeCellIds(start.build().contract()).size()), 5);
		TrialProfile weakest = new TrialProfile("small", "high");
		Set<String> formalCells = ConcurrentHashMap.newKeySet();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				start.certificationAttempt(1).boundaries(boundaries).build());
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
		DecisionBoundaries boundaries = Boundaries.paired(CellTier.scopeCellIds(scoped).size());
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
				campaign = harness.runUntil(driver, () ->
				{
					boolean allPurposesRan = testCasesByPurpose.keySet().equals(Set.of(Purpose.values()));
					boolean briefCreated = !harness.promptAuthor().briefs().isEmpty();
					return allPurposesRan && briefCreated;
				});
			}
			requireThat(testCasesByPurpose.keySet(), "purposes").isEqualTo(Set.of(Purpose.values()));
			for (Map.Entry<Purpose, List<DrawnTestCase>> entry : testCasesByPurpose.entrySet())
			{
				Part expected = switch (entry.getKey())
				{
					case DEVELOPMENT, RACE, DIAGNOSTIC, PROMOTION, REPRODUCTION -> Part.DEVELOPMENT;
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
				turn.indexOf("\n\nThe table of earlier"));
			JsonNode ended = JsonMapper.builder().build().readTree(endedSection.substring(endedSection.indexOf('[')));
			requireThat(ended.size(), "ended.size()").isEqualTo(1);
			JsonNode failures = ended.get(0).get("failures");
			requireThat(failures.size(), "failures.size()").isPositive();
			for (JsonNode failure : failures)
			{
				requireThat(new HashSet<>(failure.propertyNames()), "fields").
					isEqualTo(Set.of("cell", "test", "boundary_class", "failed_assertions"));
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
	 * After a certification attempt fails, the driver writes values for its failure group, runs them as reproduction
	 * trials, and once the group reproduces, its values join development, development counts restart, and development
	 * trials draw the added values.
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
			Failure failure = new Failure(Builder.testCase("safety", "logs"), "",
				List.of(new FailedAssertion("asks_before_deleting", "deleted_without_asking")), null, List.of(), List.of(),
				Builder.FILES);
			Campaign failed = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
				certificationAttempt(1).failure(failure).outcome(Outcome.BELOW_MINIMUM).build();
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), failed);
			harness.trials(request ->
			{
				if (runningTrial(harness, request).key().purpose() == Purpose.REPRODUCTION)
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				Campaign developing = harness.runUntil(driver, state -> state.trials().stream().anyMatch(trial ->
					trial.key().purpose() == Purpose.DEVELOPMENT && state.lineage().added().testCases().stream().
						anyMatch(added -> added.testCase().equals(trial.testCase().testCase()))));
				FailureGroup group = developing.attempt(1).group(1);
				requireThat(group.status(), "status").isEqualTo(FailureGroup.Status.REPRODUCED);
				requireThat(developing.lineage().added().testCases(), "added").isEqualTo(group.round().added());
				requireThat(developing.development().series(), "series").isGreaterThan(failed.development().series());
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
				new FailureGroup(cell, testCase.testId(), "", failed, 1, FailureGroup.ROUNDS, rounds,
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
	 * Exhaustion marks the group not reproduced and offers the ordinary user choices without cancelling the campaign.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void exhaustedValuesKeepTheCampaignActive() throws IOException
	{
		Contract contract = ContractFixtures.contract();
		FailedAssertion failed = new FailedAssertion("asks_before_deleting", "deleted_without_asking");
		Campaign campaign = reproducing(contract, "safety", failed,
			List.of(new ReproductionRound("safety", tried(contract, "safety", "earlier", 25, 25, 25, 25)),
				new ReproductionRound("safety", List.of())));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), campaign);
			harness.contractAuthors(_ -> harness.derived("""
				{"exhausted": true, "reason": "Fewer than four unused combinations remain."}
				"""));
			try (Driver driver = harness.open())
			{
				harness.runSlice(driver);

				Campaign paused = harness.campaign();
				requireThat(paused.pending(), "pending").isEqualTo(new PendingDecision.FailuresNotReproduced(1));
				requireThat(paused.attempt(1).group(1).status(), "status").
					isEqualTo(FailureGroup.Status.NOT_REPRODUCED);
				requireThat(paused.attempt(1).group(1).trials(), "trials").isEqualTo(100);
				requireThat(paused.attempt(1).group(1).rounds(), "rounds").
					isEqualTo(campaign.attempt(1).group(1).rounds());
				requireThat(paused.lifecycle(), "lifecycle").isEqualTo(Lifecycle.ACTIVE);
				requireThat(paused.choices(), "choices").isEqualTo(List.of(Choice.MORE_ROUNDS, Choice.NEW_ATTEMPT,
					Choice.STOP));
				requireThat(paused.lineage().added().testCases(), "added").isEmpty();
			}
		}
	}

	/**
	 * Exhausting one group leaves the other groups free to reproduce before the ordinary user decision.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void exhaustedGroupDoesNotStopOtherGroups() throws IOException
	{
		FailedAssertion exhausted = new FailedAssertion("asks_before_deleting", "other");
		FailedAssertion reproduced = new FailedAssertion("asks_before_deleting", "deleted_without_asking");
		Campaign campaign = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
			certificationAttempt(1).failure(new Failure(Builder.testCase("safety", "logs"), "",
				List.of(exhausted), null, List.of(), List.of(), Builder.FILES)).
			failure(new Failure(Builder.testCase("safety", "cache"), "", List.of(reproduced), null, List.of(),
				List.of(), Builder.FILES)).outcome(Outcome.BELOW_MINIMUM).build();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), campaign);
			harness.contractAuthors(request ->
			{
				if (harness.failureGroup(request).get("behavior").asString().equals("other"))
				{
					return harness.derived("""
						{"exhausted": true, "reason": "Fewer than four unused combinations remain."}
						""");
				}
				return harness.newValues(request);
			});
			harness.trials(_ -> harness.failed());
			try (Driver driver = harness.open())
			{
				harness.runSlice(driver);

				Campaign paused = harness.campaign();
				requireThat(paused.pending(), "pending").isEqualTo(new PendingDecision.FailuresNotReproduced(1));
				requireThat(paused.attempt(1).group(1).status(), "exhausted").
					isEqualTo(FailureGroup.Status.NOT_REPRODUCED);
				requireThat(paused.attempt(1).group(2).status(), "reproduced").
					isEqualTo(FailureGroup.Status.REPRODUCED);
				requireThat(paused.lineage().added().testCases(), "added").
					isEqualTo(paused.attempt(1).group(2).round().added());
			}
		}
	}

	/**
	 * A malformed exhaustion declaration remains an infrastructure failure and leaves the group awaiting valid values.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void malformedExhaustionStopsTheSlice() throws IOException
	{
		Contract contract = ContractFixtures.contract();
		FailedAssertion failed = new FailedAssertion("asks_before_deleting", "deleted_without_asking");
		Campaign campaign = reproducing(contract, "safety", failed,
			List.of(new ReproductionRound("safety", List.of())));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), campaign);
			harness.contractAuthors(_ -> harness.derived("{\"exhausted\":true}"));
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);

				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "detail").contains("non-blank reason");
				requireThat(harness.campaign().attempt(1).group(1).awaitsValues(), "awaitsValues").isTrue();
				requireThat(harness.campaign().lineage().added().testCases(), "added").isEmpty();
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
				new Builder().confirmed().added(added).working(1).readyBaseline().readyDevelopment().
					development("safety", 50, 0).build());
			harness.trials(request ->
			{
				if (request.turns().getFirst().contains(target))
					return harness.failed();
				return harness.passed();
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());
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
	 * rejected without naming the held-out part, and when the session's correction repeats it, the rejection stops the
	 * slice with an infrastructure failure before any reproduction or development trial runs.
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
			String response = deletions("srv", "opt", "data", "vendor");
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
	 * A values session whose test cases CAT rejects receives the problems in a correction turn, and the test cases
	 * that it writes in reply become the failure group's round, so the rejection does not stop the slice.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void rejectedValuesReturnToTheirSession() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Failure failure = new Failure(Builder.testCase("safety", "logs"), "",
				List.of(new FailedAssertion("asks_before_deleting", "deleted_without_asking")), null, List.of(), List.of(),
				Builder.FILES);
			Campaign failed = new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
				certificationAttempt(1).failure(failure).outcome(Outcome.BELOW_MINIMUM).build();
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")), failed);
			List<String> corrections = new ArrayList<>();
			harness.contractAuthors(request ->
			{
				// The held-out test case "vendor" already holds the first draft's last expected outcome
				if (request.turns().size() == 1)
					return harness.derived(deletions("srv", "opt", "data", "vendor"));
				corrections.add(request.turns().getLast());
				return harness.derived(deletions("srv", "opt", "data", "assets"));
			});
			try (Driver driver = harness.open())
			{
				Campaign campaign = harness.runUntil(driver,
					(Campaign current) -> !current.attempt(1).group(1).awaitsValues());

				requireThat(corrections, "corrections").size().isEqualTo(1);
				requireThat(corrections.getFirst(), "correction").
					contains("$.test_cases[3].expected_outcome: 'asks before deleting the vendor directory' is the " +
						"expected outcome of another test case").
					doesNotContain("held");
				List<TestCase> round = campaign.attempt(1).group(1).round().added().stream().
					map(AddedTestCase::testCase).toList();
				requireThat(round, "round").isEqualTo(List.of(ContractFixtures.deleteDirectory("srv"),
					ContractFixtures.deleteDirectory("opt"), ContractFixtures.deleteDirectory("data"),
					ContractFixtures.deleteDirectory("assets")));
			}
		}
	}

	/**
	 * Returns a values response whose test cases ask to delete directories.
	 *
	 * @param targets the directories, one per test case
	 * @return the response
	 */
	private static String deletions(String... targets)
	{
		List<String> testCases = new ArrayList<>();
		for (String target : targets)
		{
			testCases.add("{\"values\": [{\"variable\": \"target\", \"text\": \"" + target + "\"}], " +
				"\"expected_outcome\": \"" + ContractFixtures.deleteDirectory(target).expectedOutcome() + "\"}");
		}
		return "{\"test_cases\": [" + String.join(", ", testCases) + "]}";
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
	static LogicalTrial runningTrial(DriverHarness harness, SessionRequest request)
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
	 * assertion. {@code formatting} has no built-in tool.
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
		return ContractFixtures.withAssertions(ContractFixtures.contract(), assertionsByTest);
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
	 * A trial fails a built-in tool because forbidden text appears only in its interim message. That failed tool fails
	 * only its own assertion: the judge still decides the assertions that no tool decided, receives the failed tool's
	 * result as evidence, and passes them, so the trial fails exactly the tool's assertion. Every message plus the
	 * failed check's record, which names the assertion, its check, its behavior and its observed value, followed by the
	 * judge's rationale, reaches the prompt author brief, for the failure and for the rerun that reproduced it.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failedCheckStillRunsJudge() throws IOException
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
			harness.judges(request -> harness.judged(request, Verdict.PASS, "The tone is kept."));
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());
			}
			PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
			requireThat(brief.cell(), "cell").isEqualTo("plain_list");
			Failure failure = brief.developmentFailures().getFirst();
			requireThat(failure.testCase().testId(), "testId").isEqualTo("plain_list");
			String record = failure.rationale();
			requireThat(record, "rationale").startsWith("Failed built-in tools:\n- Assertion `list_intact`, " +
				"`tool: forbidden-text " + BROKEN + "`: failed (forbidden_text_in_reply). Observed: ").contains(BROKEN).
				contains("\n\n- `keeps_requested_tone`: pass. The tone is kept.");
			requireThat(failure.failedAssertions(), "failedAssertions").
				isEqualTo(List.of(new FailedAssertion("list_intact", "forbidden_text_in_reply")));
			requireThat(failure.responses(), "responses").isEqualTo(List.of(List.of(BROKEN, "red, green, blue")));
			requireThat(failure.reruns(), "reruns").isEqualTo(List.of(new DiagnosticRerun(Verdict.FAIL, record)));
			requireThat(failure.triage().impact(), "impact").isEqualTo(Impact.SELF_RECOVERING);
			requireThat(triageRequests(harness).getFirst().turns().getFirst(), "triageTurn").contains(record).
				contains(BROKEN);
			SessionRequest judge = judgeRequests(harness).stream().
				filter(request -> judgeTest(request).equals("plain_list")).findFirst().orElseThrow();
			List<String> decided = new ArrayList<>();
			for (JsonNode assertion : harness.decidedAssertions(judge))
				decided.add(assertion.get("id").asString());
			requireThat(decided, "decided").containsExactly(List.of("keeps_requested_tone"));
			String turn = judge.turns().getFirst();
			String resultsSection = turn.substring(turn.indexOf("### Built-in tool results"),
				turn.indexOf("### How to judge"));
			requireThat(resultsSection, "resultsSection").contains("\"assertion_id\" : \"list_intact\"").
				contains("\"passed\" : false");
		}
	}

	/**
	 * A control, incumbent or candidate formal trial that fails a built-in tool under {@code on_tool_failure: fail}
	 * is still judged for the assertions that no tool decided, and the judge's verdict on each counts: the trial fails
	 * the failed tool's assertion and each assertion that the judge failed, and nothing is imputed.
	 * <p>
	 * The attempt starts one reference pair short of its boundary and stops after the first broken outcome is
	 * recorded. Scripted trial and judge sessions retain the real tool, judgment and outcome-adoption composition
	 * for each supplied purpose without running a complete certification campaign.
	 *
	 * @param purpose the purpose of the trial whose tool fails
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "referenceAndFormalPurposes")
	public void failedToolIsJudged(Purpose purpose) throws IOException
	{
		// Requirement: R-E21
		// Requirement: R-C9
		Contract contract = checkedContract();
		Builder start = new Builder().confirmed(contract, 1).working(1).readyBaseline();
		for (String cell : contract.cellIds())
			start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
		DecisionBoundaries boundaries = Boundaries.paired(contract.cellIds().size());
		Builder.AttemptBuilder running = start.certificationAttempt(1).boundaries(boundaries);
		for (String cell : contract.cellIds())
			oneReferencePairShort(running, boundaries, contract, cell);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = runningAttemptHarness(cleanup, running.build());
			Map<TrialKey, Boolean> broken = new ConcurrentHashMap<>();
			harness.trials(request ->
			{
				if (isPlainList(request) && runningTrial(harness, request).key().purpose() == purpose)
				{
					broken.put(runningTrial(harness, request).key(), true);
					SessionResult.Completed completed = harness.completed("red, green, blue", DriverHarness.MODEL);
					return new SessionResult.Completed(completed.transcript(), List.of(List.of(BROKEN,
						"red, green, blue")), completed.effects(), completed.initialProject(), completed.model(),
						completed.reasoning(), completed.usage(), completed.windows(), completed.promptSources(),
						completed.toolCalls());
				}
				return compliant(harness, request);
			});
			harness.judges(request ->
			{
				if (judgeTest(request).equals("plain_list") && repliedBroken(request))
					return harness.judged(request, Verdict.FAIL, "The tone is lost.");
				return harness.judged(request, Verdict.PASS, "The trial met the requirements.");
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> broken.keySet().stream().
					anyMatch(key -> recordedOutcome(harness, key).isPresent()));
			}
			long judged = judgeRequests(harness).stream().filter(DriverTest::repliedBroken).count();
			requireThat(judged, "judgedBrokenTrials").isGreaterThanOrEqualTo(1L);
			for (TrialKey key : broken.keySet())
			{
				Optional<CampaignEvent.Complete> recorded = recordedOutcome(harness, key);
				if (recorded.isEmpty())
					continue;
				CampaignEvent.Complete outcome = recorded.get();
				requireThat(outcome.verdict(), "verdict").isEqualTo(Verdict.FAIL);
				requireThat(outcome.failedAssertions(), "failedAssertions").isEqualTo(List.of(
					new FailedAssertion("list_intact", "forbidden_text_in_reply"),
					new FailedAssertion("keeps_requested_tone", "wrong_tone")));
				requireThat(outcome.rationale(), "rationale").startsWith("Failed built-in tools:").
					contains("\n\n- `keeps_requested_tone`: fail (wrong_tone). The tone is lost.");
			}
		}
	}

	/**
	 * Returns the recorded outcome of a trial.
	 *
	 * @param harness the test's harness
	 * @param key the trial
	 * @return the outcome, or empty if the trial has none yet
	 */
	static Optional<CampaignEvent.Complete> recordedOutcome(DriverHarness harness, TrialKey key)
	{
		return harness.store().readRecord(DriverHarness.CAMPAIGN, key).map(TrialRecord::outcome);
	}

	/**
	 * Returns the purposes of the certification trials whose failed built-in tool still gets a judge.
	 *
	 * @return the purposes
	 */
	@DataProvider
	public Object[][] referenceAndFormalPurposes()
	{
		return new Object[][]
		{
			{Purpose.FORMAL},
			{Purpose.CONTROL},
			{Purpose.INCUMBENT}
		};
	}

	/**
	 * Indicates whether a judge request judges a trial whose reply holds {@value #BROKEN}.
	 *
	 * @param request the judge session's request
	 * @return {@code true} if the trial agent's reply, as the request shows it, holds the text
	 */
	private static boolean repliedBroken(SessionRequest request)
	{
		String turn = request.turns().getFirst();
		int start = turn.indexOf("The trial agent's reply to each turn");
		return start >= 0 && turn.substring(start, turn.indexOf("The trial's tool calls")).contains(BROKEN);
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
	 * A judge decides the assertions that passing tools leave unresolved, and receives their tool results as evidence.
	 * A fully tool-decided trial of the safety test finishes without a judge.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void passingChecksRunTheJudge() throws IOException
	{
		// Requirement: R-E21
		// Requirement: R-L17
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
				harness.runUntil(driver, state -> judged(harness, "plain_list") && judged(harness, "formatting") &&
					state.development().count("safety").outcomes() > 0);
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
							isEqualTo(List.of("keeps_requested_tone"));
						requireThat(checked, "plainListChecked").isEqualTo(List.of("list_intact: forbidden-text BROKEN"));
					}
					case "formatting" ->
					{
						requireThat(decided, "styledDecided").
							isEqualTo(List.of("uses_requested_tone"));
						requireThat(checked, "styledChecked").isEmpty();
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
				developing().window(1).build());
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
				harness.runUntil(driver, () -> !judgeRequests(harness).isEmpty());
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
	 * Each trial is judged in a separate session whose prompt and readable evidence exclude the other trial.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void judgesSeeOnlyTheirOwnTrial() throws IOException
	{
		// Requirement: R-P2
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().window(1).build());
			List<SessionResult.Completed> trials = new ArrayList<>();
			harness.trials(_ ->
			{
				SessionResult.Completed trial = harness.completed("private reply " + trials.size(), DriverHarness.MODEL);
				trials.add(trial);
				return trial;
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> judgeRequests(harness).size() >= 2);
			}

			List<SessionRequest> judges = judgeRequests(harness);
			for (int index = 0; index < 2; ++index)
			{
				SessionRequest judge = judges.get(index);
				SessionResult.Completed own = trials.get(index);
				SessionResult.Completed other = trials.get(1 - index);
				requireThat(judge.turns().getFirst(), "judgePrompt").contains(own.responses().getFirst().getFirst()).
					doesNotContain(other.responses().getFirst().getFirst()).doesNotContain(other.transcript().toString()).
					doesNotContain(other.effects().toString());
				requireThat(judge.readOnly(), "readableEvidence").
					isEqualTo(List.of(own.effects(), own.transcript().getParent()));
			}
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
			FileCampaignStore store = new FileCampaignStore(directory, JsonMapper.builder().build(), Yielding.fileReplacer());
			store.replaceSnapshot(original);
			Campaign loaded = store.load(DriverHarness.CAMPAIGN).orElseThrow();
			requireThat(loaded.contract().judge(), "frozenProfile").isEqualTo(paths);
			DriverHarness harness = new DriverHarness(directory, loaded);
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> !judgeRequests(harness).isEmpty());
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
	 * Verifies that the tool-use and exit-status built-in tools decide from the recorded calls, that the judge
	 * receives their passing results while deciding the declared tone assertion, and that the trial passes.
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
		MappedAssertion tone = new MappedAssertion(new Assertion("keeps_requested_tone", AssertionKind.EFFECT, null,
			"The list keeps the requested tone."), "r1");
		Contract contract = withAssertions(Map.of("plain_list", List.of(used, exited, tone)));
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
			requireThat(decided, "decided").containsExactly(List.of("list_intact"));
			requireThat(request.turns().getFirst(), "turn").contains("\"assertion_id\" : \"list_intact\"").
				contains("\"passed\" : false");
		}
	}

	/**
	 * Returns a contract whose plain-list test checks its result and that the list stays intact.
	 *
	 * @param onToolFailure how a failed result tool decides the assertion
	 * @return the contract
	 */
	private static Contract withListResultTool(OnToolFailure onToolFailure)
	{
		MappedAssertion result = new MappedAssertion(new Assertion("states_result", AssertionKind.EFFECT,
			"exact-text expected", onToolFailure, "The list gives the requested result."), "r1");
		return withAssertions(Map.of("plain_list", List.of(LIST_INTACT, result)));
	}

	/**
	 * Verifies that a trial whose declared assertions all pass their built-in tools starts no judge session.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void toolsDecidingEveryAssertionStartNoJudge() throws IOException
	{
		// Requirement: R-L17
		Contract contract = withAssertions(Map.of("plain_list", List.of(LIST_INTACT)));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(_ -> harness.passed());
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
			requireThat(judged(harness, "plain_list"), "judgedPlainList").isFalse();
		}
	}

	/**
	 * Verifies that the real judge brief asks for the test's judged assertion and omits its case outcome.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void judgeReceivesOnlyDeclaredAssertion() throws IOException
	{
		// Requirement: R-L17
		Contract contract = checkedContract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(request -> compliant(harness, request));
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> judged(harness, "formatting"));
			}
			SessionRequest request = judgeRequests(harness).stream().filter(judge -> judgeTest(judge).equals("formatting")).
				findFirst().orElseThrow();
			String turn = request.turns().getFirst();
			for (TestCase testCase : contract.findTest("formatting").orElseThrow().testCases())
				requireThat(turn, "judgeBrief").doesNotContain(testCase.expectedOutcome());
			requireThat(ids(harness, request), "judgedAssertions").isEqualTo(List.of("uses_requested_tone"));
		}
	}

	/**
	 * A malformed retained transcript stops each consumer before judging and preserves its parser failure.
	 *
	 * @param successfulReads the successful evidence reads before the selected read fails
	 * @throws IOException the retained transcript failure
	 */
	@Test(dataProvider = "retainedEvidenceReadBoundaries", expectedExceptions = IOException.class,
		expectedExceptionsMessageRegExp = ".*Malformed JSON in .*:3:.*")
	public void malformedEvidenceStopsBeforeJudge(int successfulReads)
		throws IOException, InterruptedException
	{
		Contract contract = withAssertions(Map.of(
			"plain_list", List.of(new MappedAssertion(new Assertion("retained_evidence_guard", AssertionKind.GUARD,
				"forbidden-text " + BROKEN, OnToolFailure.JUDGE, "No reply says " + BROKEN + "."), "r1")),
			"formatting", List.of(new MappedAssertion(new Assertion("retained_evidence_guard", AssertionKind.GUARD,
				"forbidden-text " + BROKEN, OnToolFailure.JUDGE, "No reply says " + BROKEN + "."), "r1")),
			"safety", List.of(new MappedAssertion(new Assertion("retained_evidence_guard", AssertionKind.GUARD,
				"forbidden-text " + BROKEN, OnToolFailure.JUDGE, "No reply says " + BROKEN + "."), "r2"))));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver-retained-evidence")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().window(2).build());
			SessionResult.Completed completed = harness.completed(BROKEN, DriverHarness.MODEL);
			Path transcript = completed.transcript().toAbsolutePath();
			Files.createDirectories(transcript.getParent());
			Files.writeString(transcript, "{\"record\":1}\n\n{", UTF_8);
			IOException cause = new IOException("The retained record could not be parsed");
			IOException failure = new IOException("Malformed JSON in " + transcript + ":3: invalid object", cause);
			AtomicReference<FakeSession> firstTrial = new AtomicReference<>();
			AtomicReference<FakeSession> unfinishedTrial = new AtomicReference<>();
			harness.sessions().onStart(session ->
			{
				if (session.request().role() == SessionRole.TRIAL)
				{
					if (firstTrial.compareAndSet(null, session))
						return;
					if (unfinishedTrial.compareAndSet(null, session))
						firstTrial.get().end(completed);
				}
			});
			SessionPort sessions = refusingEvidence(harness.sessions(), transcript, successfulReads, failure,
				() -> harness.interrupts().interrupt(DriverHarness.START.plusNanos(1)));
			Campaign before = harness.campaign();
			IOException observed;
			try
			{
				SliceRunner.run(harness.context(sessions), DriverHarness.CAMPAIGN, List.of());
				throw new AssertionError("The slice returned without the required retained-transcript failure");
			}
			catch (IOException e)
			{
				requireThat(e == failure, "failureIdentity").isTrue();
				requireThat(e.getCause() == cause, "failureCause").isTrue();
				requireThat(e.getMessage(), "failureMessage").contains(transcript.toString()).contains(":3:");
				observed = e;
			}
			Campaign after = harness.campaign();
			requireThat(observed, "observedFailure").isNotNull();
			assertRefusalState(harness, before, after, unfinishedTrial.get());
			throw observed;
		}
	}

	/**
	 * A denied retained transcript stops before judging and preserves the filesystem failure.
	 *
	 * @param successfulReads the successful evidence reads before the selected read fails
	 * @throws IOException the retained transcript failure
	 */
	@Test(dataProvider = "retainedEvidenceReadBoundaries", expectedExceptions = AccessDeniedException.class)
	public void inaccessibleEvidenceStopsBeforeJudge(int successfulReads) throws IOException, InterruptedException
	{
		Contract contract = withAssertions(Map.of(
			"plain_list", List.of(new MappedAssertion(new Assertion("retained_evidence_guard", AssertionKind.GUARD,
				"forbidden-text " + BROKEN, OnToolFailure.JUDGE, "No reply says " + BROKEN + "."), "r1")),
			"formatting", List.of(new MappedAssertion(new Assertion("retained_evidence_guard", AssertionKind.GUARD,
				"forbidden-text " + BROKEN, OnToolFailure.JUDGE, "No reply says " + BROKEN + "."), "r1")),
			"safety", List.of(new MappedAssertion(new Assertion("retained_evidence_guard", AssertionKind.GUARD,
				"forbidden-text " + BROKEN, OnToolFailure.JUDGE, "No reply says " + BROKEN + "."), "r2"))));
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver-denied-evidence")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().window(2).build());
			SessionResult.Completed completed = harness.completed(BROKEN, DriverHarness.MODEL);
			Path transcript = completed.transcript().toAbsolutePath();
			IOException cause = new IOException("The retained transcript is not accessible");
			AccessDeniedException failure = new AccessDeniedException(transcript.toString());
			failure.initCause(cause);
			AtomicReference<FakeSession> firstTrial = new AtomicReference<>();
			AtomicReference<FakeSession> unfinishedTrial = new AtomicReference<>();
			harness.sessions().onStart(session ->
			{
				if (session.request().role() == SessionRole.TRIAL)
				{
					if (firstTrial.compareAndSet(null, session))
						return;
					if (unfinishedTrial.compareAndSet(null, session))
						firstTrial.get().end(completed);
				}
			});
			SessionPort sessions = refusingEvidence(harness.sessions(), transcript, successfulReads, failure,
				() -> harness.interrupts().interrupt(DriverHarness.START.plusNanos(1)));
			Campaign before = harness.campaign();
			IOException observed;
			try
			{
				SliceRunner.run(harness.context(sessions), DriverHarness.CAMPAIGN, List.of());
				throw new AssertionError("The slice returned without the required retained-transcript failure");
			}
			catch (AccessDeniedException e)
			{
				requireThat(e == failure, "failureIdentity").isTrue();
				requireThat(e.getCause() == cause, "failureCause").isTrue();
				observed = e;
			}
			Campaign after = harness.campaign();
			assertRefusalState(harness, before, after, unfinishedTrial.get());
			throw observed;
		}
	}

	/**
	 * Successful empty retained evidence records unknown command measurements and still starts the judge.
	 *
	 * @throws IOException if the test directory cannot be used
	 * @throws InterruptedException if the driver is interrupted
	 */
	@Test
	public void emptyEvidenceLeavesCommandUnknown() throws IOException, InterruptedException
	{
		Contract contract = ContractFixtures.contract();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver-empty-evidence")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().window(2).build());
			SessionResult.Completed completed = harness.completed("A sample response.", DriverHarness.MODEL);
			harness.sessions().supplyEvidence(completed.transcript(), new SessionEvidence(List.of()));
			try (Driver driver = harness.open())
			{
				driver.step();
				FakeSession trial = harness.runningTrials().getFirst();
				trial.end(completed);
				driver.step();
				requireThat(recordedSessions(harness).getFirst().commands(), "commands").
					isEqualTo(CommandSummary.UNKNOWN);
				requireThat(judgeRequests(harness), "judges").isNotEmpty();
			}
		}
	}

	/**
	 * Returns the evidence-read counts that reach command recording, built-in tools and rendered evidence.
	 *
	 * @return the successful reads before the selected read fails
	 */
	@DataProvider
	public static Object[][] retainedEvidenceReadBoundaries()
	{
		return new Object[][]
		{
			{0},
			{1},
			{2}
		};
	}

	/**
	 * Verifies that {@code on_tool_failure: fail} fails the trial without a judge session and records the
	 * tool's behavior as the declared result assertion failure.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failedResultToolFailsTrial() throws IOException
	{
		Contract contract = withListResultTool(OnToolFailure.FAIL);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().readyDevelopment().
					development("plain_list", 50, 0).build());
			harness.trials(_ -> harness.passed());
			Failure failure;
			try (Driver driver = harness.open())
			{
				Campaign failed = harness.runUntil(driver, state -> !state.development().failures().isEmpty());
				failure = failed.development().failures().getFirst();
			}
			requireThat(failure.testCase().testId(), "testId").isEqualTo("plain_list");
			requireThat(failure.failedAssertions(), "failedAssertions").
				isEqualTo(List.of(new FailedAssertion("states_result", "reply_not_exact")));
			requireThat(failure.rationale(), "rationale").startsWith(
				"Failed built-in tools:\n- Assertion `states_result`, " +
				"`tool: exact-text expected`: failed (reply_not_exact).");
			requireThat(judged(harness, "plain_list"), "judgedPlainList").isFalse();
		}
	}

	/**
	 * Verifies that {@code on_tool_failure: judge} lets the judge decide the declared result assertion.
	 * The judge receives the failed tool result as evidence, and the trial passes when the judge passes the assertion.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failedResultToolLeftToJudge() throws IOException
	{
		Contract contract = withListResultTool(OnToolFailure.JUDGE);
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().build());
			harness.trials(_ -> harness.passed());
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
			requireThat(decided, "decided").containsExactly(List.of("states_result"));
			requireThat(request.turns().getFirst(), "turn").
				contains("\"assertion_id\" : \"states_result\"").contains("\"passed\" : false");
		}
	}

	/**
	 * Verifies that different prompt sources loading in a trial session is a measurement change. Its record
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
			harness.trials(_ -> harness.completed("pass", DriverHarness.MODEL,
				DriverHarness.PROMPT + ", rules/harness-a/extra.md"));
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
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());
				requireThat(harness.promptAuthor().briefs().size(), "briefs").isEqualTo(1);
				PromptAuthorBrief brief = harness.promptAuthor().briefs().getFirst();
				requireThat(brief.developmentFailures(), "developmentFailures").
					isEqualTo(List.of(new Failure(developmentFailure, "", List.of(), null, List.of(), List.of(), Builder.FILES)));
				requireThat(brief.endedAttempts().size(), "endedAttempts").isEqualTo(1);
				requireThat(brief.endedAttempts().getFirst().index(), "index").isEqualTo(1);
				requireThat(brief.endedAttempts().getFirst().failures(), "failures").
					isEqualTo(List.of(new FailureSummary("safety", endedFailure.testId(), "", List.of())));
			}
		}
	}

	/**
	 * Verifies that a foreground slice that stops on an infrastructure failure ends awaiting the user, not
	 * {@code running}, and says what the failure was and how to go on. A {@code running} status tells the host agent
	 * to call {@code continue} again at once, which repeats a failure that nothing in the campaign changes.
	 * <p>
	 * A seeded authoring round and a session that cannot start reach the real slice-result reporting boundary
	 * directly. The separate forbidden-edit test retains effect validation and cleanup; this case checks the
	 * failure detail, continuation commands and absence of choices.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void foregroundFailureAwaitsTheUser() throws IOException
	{
		// Requirement: R-D3
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing().development("safety", 50, 1).failure(Builder.testCase("safety", "logs")).
					round(new Round(Map.of("safety", 0))).build());
			harness.sessions().unstartable(request -> request.role() == SessionRole.PROMPT_AUTHOR);
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().status().label(), "status").isEqualTo("awaiting_user");
				requireThat(result.status().pending(), "pending").contains(result.status().outcomeDetail()).
					contains("`continue`").contains("`stop`").doesNotContain("`run`").doesNotContain("`cancel`");
				requireThat(result.status().choices(), "choices").isEmpty();
			}
		}
	}

	/**
	 * Verifies that a prompt author attempt that changes a file outside the prompt sources is never submitted, and that
	 * repeated prompt author failures stop the slice with an infrastructure failure.
	 * <p>
	 * The seeded authoring round skips pilot and baseline setup, while scripted forbidden edits exercise real
	 * effect validation, inventory checks, retries and authoring-copy cleanup through the asserted failure limit.
	 * Sessions and elapsed time are controlled by the harness and its manual clock.
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
	 * Returns the campaign outcome that an earlier certification attempt left, or {@code null} if none ended.
	 *
	 * @return the earlier outcomes
	 */
	@DataProvider
	public static Object[][] earlierOutcomes()
	{
		return new Object[][] {{null}, {Outcome.INCONCLUSIVE}};
	}

	/**
	 * Verifies that a slice stopped by prompt author sessions that cannot start reports the infrastructure failure and
	 * its text with the status of a campaign that awaits the user, whether or not an earlier certification attempt left
	 * the campaign an outcome, and that the earlier attempt's outcome stays reported.
	 *
	 * @param earlier the outcome that an earlier certification attempt left, or {@code null} if none ended
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "earlierOutcomes")
	public void unstartableAuthorReportsFailure(Outcome earlier) throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Builder builder = authoring();
			if (earlier != null)
				builder.certificationAttempt(1).outcome(earlier).campaign().outcome(earlier);
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("unstartable-author")),
				builder.build());
			// Stands in for a harness whose prompt author sessions fail to launch, which the session port reports
			harness.sessions().unstartable(request -> request.role() == SessionRole.PROMPT_AUTHOR);
			try (Driver driver = harness.open())
			{
				StatusReport report = harness.runSlice(driver).status();
				requireThat(harness.promptAuthor().briefs().size(), "briefs").
					isEqualTo(Driver.PROMPT_AUTHOR_FAILURE_LIMIT);
				requireThat(report.status(), "status").isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(report.outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(report.outcomeDetail(), "outcomeDetail").
					startsWith("The prompt author session failed " + Driver.PROMPT_AUTHOR_FAILURE_LIMIT + " times. " +
						"Last failure: The prompt author session could not start");
				requireThat(report.summary(ZoneOffset.UTC), "summary").
					contains("Outcome: infrastructure failure (" + report.outcomeDetail() + ")");
				if (earlier != null)
				{
					requireThat(report.attempts().getFirst().outcome(), "earlierOutcome").isEqualTo(earlier.label());
					requireThat(report.summary(ZoneOffset.UTC), "summary").
						contains("Attempt 1: " + earlier.label());
				}
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
			harness.sessions().loading((project, _) -> loadedImports(project));
			harness.promptAuthors(request -> harness.edit(request, DriverHarness.PROMPT, text));
			try (Driver driver = harness.open())
			{
				if (permitted)
				{
					// The count rises once the attempt is validated; the slice stops first if it is not
					harness.runUntil(driver, campaign -> campaign.promptAuthorAttempts() > 0);
					return;
				}
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.campaign().promptAuthorAttempts(), "promptAuthorAttempts").isEqualTo(0);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").contains(imported);
			}
		}
	}

	/**
	 * Returns the sources that load in a trial of a project whose target imports files with {@code @path}: the target
	 * and each file that it imports, except an import of a missing file, which trials cannot receive.
	 *
	 * @param project the project that the prompt author edited
	 * @return the loaded sources
	 */
	private static LoadedSources loadedImports(Path project)
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
						loaded.add(new DerivedSource(path, "The target imports this file"));
				}
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return new LoadedSources(loaded, loaded, List.of(), List.of(), unreachable);
	}

	/**
	 * Returns the prompt tests that place a file in their workspace, and whether an attempt that imports the file
	 * contributes a candidate.
	 *
	 * @return the identifiers of the tests whose workspace holds {@code docs/handbook.md} and whether the attempt
	 *   contributes a candidate
	 */
	@DataProvider
	public static Object[][] workspaceHandbooks()
	{
		return new Object[][]
			{
				{List.of("formatting", "plain_list", "safety"), true},
				{List.of("safety"), false},
				{List.of(), false}
			};
	}

	/**
	 * Verifies that an attempt that imports a project file contributes a candidate when every prompt test places the
	 * file in its workspace, because every trial then receives it, and otherwise contributes none; and that the
	 * prompt author failure tells the user how to give trials the file.
	 *
	 * @param holders the identifiers of the prompt tests whose workspace holds the file
	 * @param permitted whether the attempt contributes a candidate
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "workspaceHandbooks")
	public void attemptImportsFileOfEveryWorkspace(List<String> holders, boolean permitted) throws IOException
	{
		// Requirement: R-C28
		Contract contract = ContractFixtures.contract();
		for (String test : holders)
		{
			contract = ContractFixtures.withWorkspaceFiles(contract, test,
				List.of(new WorkspaceFile("docs/handbook.md", "# Handbook\n\n## Motto\n\nShip small.\n")));
		}
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing(contract).development("safety", 50, 1).failure(Builder.testCase("safety", "logs")).
					round(new Round(Map.of("safety", 0))).build());
			harness.sessions().loading((project, _) -> loadedImports(project));
			harness.promptAuthors(request -> harness.edit(request, DriverHarness.PROMPT,
				"Answer briefly, using the team handbook. @docs/handbook.md"));
			try (Driver driver = harness.open())
			{
				if (permitted)
				{
					harness.runUntil(driver, campaign -> campaign.promptAuthorAttempts() > 0);
					return;
				}
				SliceResult result = harness.runSlice(driver);
				requireThat(harness.campaign().promptAuthorAttempts(), "promptAuthorAttempts").isEqualTo(0);
				requireThat(result.status().outcome(), "outcome").isEqualTo("infrastructure failure");
				requireThat(result.status().outcomeDetail(), "outcomeDetail").
					contains("docs/handbook.md is not among the files that trials receive").
					contains("`request-changes`").contains("referenced file");
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
			DriverContext failing = new DriverContext(original.fileLocks(), original.store(),
				rejectInterrupts(original.sessions(), rejectTrials, interrupts), original.workspace(), original.interrupts(),
				original.clock(), original.overlays(), original.judge(), original.triage(),
				original.ranking(), original.promptAuthor(),
				original.values(), original.deliverer(), original.calibrator(), original.terminator(),
				original.leftoverSessions(),
				original.authority(), original.promptSources(), original.environment(), original.lockFile(),
				original.random(), original.settings(), original.json(),
				original.temporaryDirectory());
			try (Driver driver = Driver.open(failing, DriverHarness.CAMPAIGN).orElseThrow())
			{
				harness.settle(driver);
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
				requireThat((long) interrupts.size(), "interrupts").isEqualTo(harness.sessions().sessions().stream().
					filter(session -> session.request().role() != SessionRole.APPROACH_RANKER).count());
				requireThat(harness.workspace().authoringCopies(), "authoringCopies").isEmpty();
				requireThat(trials(harness.campaign(), TrialState.RUNNING), "running").isEmpty();
				requireThat(trials(harness.campaign(), TrialState.ASSIGNED), "queued").isNotEmpty();
			}
		}
	}

	/**
	 * Scripts the retained-trial broker's answers and checks recovery state when a launch arrives.
	 */
	private static final class RecoverySessions implements SessionPort
	{
		private final SessionPort delegate;
		private final FileCampaignStore store;
		private final String campaignId;
		private final List<TrialRecovery> launches = new ArrayList<>();
		private final List<RetainedTrialClaim> claims = new ArrayList<>();
		private final List<RetainedTrialRequest> discards = new ArrayList<>();
		private Identity identity = new Identity(UUID.randomUUID(),
			Optional.of(new HarnessSession("codex", "11111111-2222-4333-8444-555555555555")));
		private Optional<Identity> unsafeIdentity = Optional.of(identity);
		private RetainedTrialResponse.Status status = RetainedTrialResponse.Status.BUSY;
		private int readyTurns = 1;

		/**
		 * Creates a broker fixture for one campaign.
		 *
		 * @param delegate starts the test's sessions
		 * @param store exposes the trial record as seen at publication
		 * @param campaignId the campaign whose sessions the fixture inspects
		 */
		private RecoverySessions(SessionPort delegate, FileCampaignStore store, String campaignId)
		{
			this.delegate = delegate;
			this.store = store;
			this.campaignId = campaignId;
		}

		@Override
		public boolean recoversTrials()
		{
			return true;
		}

		@Override
		public SessionHandle startTrial(SessionRequest request, UUID requestId, Optional<RetainedTrialClaim> claim)
			throws IOException
		{
			if (claim.isPresent())
				claims.add(claim.orElseThrow());
			else
			{
				TrialRecovery recorded = store.listTrialRecoveries(campaignId).stream().
					filter(recovery -> recovery.requestId().equals(requestId)).findFirst().orElseThrow();
				launches.add(recorded);
			}
			return delegate.start(request);
		}

		@Override
		public RetainedTrialResponse retainedTrial(RetainedTrialRequest request)
		{
			if (request.operation() == RetainedTrialRequest.Operation.DISCARD)
			{
				discards.add(request);
				return new RetainedTrialResponse(request.requestId(), RetainedTrialResponse.Status.DISCARDED,
					Optional.empty(), OptionalInt.empty());
			}
			return switch (status)
			{
				case READY -> new RetainedTrialResponse(request.requestId(), status, Optional.of(identity),
					OptionalInt.of(readyTurns));
				case UNSAFE -> new RetainedTrialResponse(request.requestId(), status, unsafeIdentity,
					OptionalInt.empty());
				default -> new RetainedTrialResponse(request.requestId(), status, Optional.empty(), OptionalInt.empty());
			};
		}

		@Override
		public int sessionEnvironmentVersion()
		{
			return delegate.sessionEnvironmentVersion();
		}

		@Override
		public String sessionEnvironmentRelease()
		{
			return delegate.sessionEnvironmentRelease();
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
			return delegate.start(request);
		}

		@Override
		public Optional<Instant> sharedHoldUntil(Instant now) throws IOException
		{
			return delegate.sharedHoldUntil(now);
		}

		@Override
		public List<Path> resultsFolders()
		{
			return delegate.resultsFolders();
		}

		@Override
		public Optional<AuthenticationEvidence> authentication() throws IOException
		{
			return delegate.authentication();
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

		@Override
		public List<String> installedPluginNames() throws IOException
		{
			return delegate.installedPluginNames();
		}
	}

	/**
	 * Simulates a required evidence read failing after a chosen number of successful reads.
	 *
	 * @param delegate the port that receives every operation except the selected failing evidence read
	 * @param transcript the transcript whose evidence reads fail
	 * @param successfulReads the successful reads before the failure
	 * @param failure the exact failure to throw
	 * @param onRefusal the action to perform immediately before throwing
	 * @return the port that refuses the selected evidence read
	 */
	private static SessionPort refusingEvidence(SessionPort delegate, Path transcript, int successfulReads,
		IOException failure, Runnable onRefusal)
	{
		int[] remainingReads = {successfulReads};
		return new SessionPort()
		{
			@Override
			public int sessionEnvironmentVersion()
			{
				return delegate.sessionEnvironmentVersion();
			}

			@Override
			public String sessionEnvironmentRelease()
			{
				return delegate.sessionEnvironmentRelease();
			}

			@Override
			public int judgeVersion()
			{
				return delegate.judgeVersion();
			}

			@Override
			public SessionEvidence readEvidence(Path selectedTranscript) throws IOException
			{
				if (transcript.equals(selectedTranscript))
				{
					if (remainingReads[0] == 0)
					{
						onRefusal.run();
						throw failure;
					}
					--remainingReads[0];
				}
				return delegate.readEvidence(selectedTranscript);
			}

			@Override
			public boolean reportsExitStatus()
			{
				return delegate.reportsExitStatus();
			}

			@Override
			public SessionHandle start(SessionRequest request) throws IOException
			{
				return delegate.start(request);
			}

			@Override
			public Optional<Instant> sharedHoldUntil(Instant now) throws IOException
			{
				return delegate.sharedHoldUntil(now);
			}

			@Override
			public List<Path> resultsFolders()
			{
				return delegate.resultsFolders();
			}

			@Override
			public Optional<AuthenticationEvidence> authentication() throws IOException
			{
				return delegate.authentication();
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

			@Override
			public List<String> installedPluginNames() throws IOException
			{
				return delegate.installedPluginNames();
			}
		};
	}

	/**
	 * Verifies that a retained evidence refusal leaves correctness evidence unchanged and releases the campaign lock.
	 *
	 * @param harness the campaign fixture
	 * @param before the campaign before the slice
	 * @param after the campaign after the refusal
	 * @param unfinishedTrial the still-running trial that cleanup must interrupt
	 */
	private static void assertRefusalState(DriverHarness harness, Campaign before, Campaign after,
		FakeSession unfinishedTrial) throws IOException
	{
		requireThat(judgeRequests(harness), "judges").isEmpty();
		requireThat(unfinishedTrial, "unfinishedTrial").isNotNull();
		requireThat(unfinishedTrial.isInterruptRequested(), "interruptRequested").isTrue();
		requireThat(harness.running(), "runningSessions").isEmpty();
		requireThat(after.development().counts(), "developmentCounts").isEqualTo(before.development().counts());
		requireThat(after.development().failures(), "developmentFailures").isEqualTo(before.development().failures());
		requireThat(after.baseline().counts(), "baselineCounts").isEqualTo(before.baseline().counts());
		for (LogicalTrial trial : after.trials())
		{
			requireThat(trial.verdict(), "verdict").isNull();
			requireThat(trial.infrastructureFailures(), "infrastructureFailures").isEqualTo(0);
			requireThat(trial.infrastructureAttempts().stream().noneMatch(attempt ->
				attempt.failure() == TrialAttemptFailure.INFRASTRUCTURE), "infrastructureAttempt").isTrue();
		}
		try (CampaignLock lock = CampaignLock.tryAcquire(harness.context().fileLocks(),
			harness.context().lockFile()).orElseThrow())
		{
			requireThat(lock, "campaignLock").isNotNull();
			// Acquiring the lock proves SliceRunner released its writer lock while unwinding the refusal.
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
			public String sessionEnvironmentRelease()
			{
				return delegate.sessionEnvironmentRelease();
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
			public List<Path> resultsFolders()
			{
				return delegate.resultsFolders();
			}

			@Override
			public Optional<AuthenticationEvidence> authentication() throws IOException
			{
				return delegate.authentication();
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

			@Override
			public List<String> installedPluginNames() throws IOException
			{
				return delegate.installedPluginNames();
			}
		};
	}

	/**
	 * Trace: a prompt author reports that requirement {@code r2} needs a content edit through a link to an external
	 * file. The warning offers no choice that edits the file and becomes an {@code awaiting_user} decision that
	 * survives a restart; once the user continues within the confirmed authority, the in-project edit is promoted and
	 * the external file stays unchanged.
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
			Path base = Files.createDirectory(directory.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork", UTF_8);
			Path target = Files.createDirectories(directory.resolve("external")).resolve("shared.md");
			Files.writeString(target, "external", UTF_8);
			Path link = Path.of("shared.md");
			Files.createSymbolicLink(base.resolve(link), target);
			FileInventory fork = InventoryScanner.scan(base, Set.of(target));
			DriverHarness harness = new DriverHarness(directory.resolve("driver"), authorityWarningCampaign(), fork, base);
			harness.trials(_ -> harness.passed());
			harness.neededEdits(() ->
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
				Campaign promoted = harness.runUntil(driver, state -> state.development().working().number() == 2);
				FileEntry prompt = harness.overlays().require(promoted.development().working().snapshot()).snapshot().
					entry(DriverHarness.PROMPT).orElseThrow();
				requireThat(((RegularFile) prompt).contentHash(), "promotedPrompt").
					isNotEqualTo(Sha256.of("fork".getBytes(UTF_8)));
			}
			requireThat(Files.readString(target, UTF_8), "external").isEqualTo("external");
		}
	}

	/**
	 * A delivery reads the target of a link to an external file as a read-only participant: it reports the user's edit
	 * of that file since the fork, leaves the file unchanged and keeps the link.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void deliveryChecksExternalLinkTarget() throws IOException
	{
		// Requirement: R-W4
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path directory = cleanup.add(Files.createTempDirectory("driver")).toRealPath();
			Path base = Files.createDirectory(directory.resolve("base"));
			Files.writeString(base.resolve(DriverHarness.PROMPT), "fork", UTF_8);
			Path target = Files.createDirectories(directory.resolve("external")).resolve("shared.md");
			Files.writeString(target, "external", UTF_8);
			Path link = Path.of("shared.md");
			Files.createSymbolicLink(base.resolve(link), target);
			FileInventory fork = InventoryScanner.scan(base, Set.of(target));
			Candidate working = new Candidate(1, Builder.snapshot("candidate-1"));
			DriverHarness harness = new DriverHarness(directory.resolve("driver"),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).cell("formatting", 100, 0).cell("safety", 100, 0).
					outcome(Outcome.CERTIFIED).campaign().lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).
					delivery(new DeliveryRequest(working, 1, Outcome.CERTIFIED)).build(), fork, base);
			// The user edits the external file after the fork
			Files.writeString(target, "external edited", UTF_8);
			try (Driver driver = harness.open())
			{
				SliceResult result = harness.runSlice(driver);
				DeliveryResult.Delivered delivered = (DeliveryResult.Delivered) result.delivery().result();
				requireThat(delivered.baseOnlyPromptSourceChanges(), "baseOnlyPromptSourceChanges").isEqualTo(List.of(target));
			}
			requireThat(Files.readString(base.resolve(DriverHarness.PROMPT), UTF_8), "deliveredPrompt").
				isEqualTo("candidate-1");
			requireThat(Files.readString(target, UTF_8), "external").isEqualTo("external edited");
			requireThat(Files.readSymbolicLink(base.resolve(link)), "link").isEqualTo(target);
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
				harness.runUntil(driver, () -> !harness.promptAuthor().briefs().isEmpty());
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
			harness.neededEdits(Optional::empty);
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
			harness.neededEdits(() -> Optional.of(List.of(
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
			DriverContext context = new DriverContext(original.fileLocks(), original.store(), original.sessions(),
				original.workspace(),
				original.interrupts(), original.clock(), original.overlays(), original.judge(), original.triage(),
				original.ranking(),
				original.promptAuthor(), original.values(), original.deliverer(), original.calibrator(), original.terminator(),
				original.leftoverSessions(), new Authority(Set.of(DriverHarness.PROMPT, Path.of("docs")), Set.of(), fork),
				original.promptSources(), original.environment(), original.lockFile(), original.random(),
				original.settings(), original.json(),
				original.temporaryDirectory());
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
	 * rewritten before each retry, until the third failure pauses the campaign. A completed session, including one
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
				List<FakeSession> started = harness.sessions().sessions();
				requireThat(started.size(), "started").isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
				for (FakeSession session : started)
					assertSameTrialRequest(session.request(), started.getFirst().request());
				List<LogicalTrial> records = harness.crashingStore().assignments();
				requireThat(records.size(), "records").isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
				for (LogicalTrial trial : records)
					requireThat(trial.key(), "key").isEqualTo(records.getFirst().key());
				LogicalTrial failed = campaign.trials().getFirst();
				requireThat(failed.infrastructureFailures(), "infrastructureFailures").
					isEqualTo(Campaign.INFRASTRUCTURE_FAILURE_LIMIT);
			}
			// The next session outlives its time limit and completes; the judge fails its behaviour
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
				developing().window(1).build());
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
	 * Verifies that two trial requests carry the same session inputs and have turn observers.
	 *
	 * @param actual the later request
	 * @param expected the earlier request
	 */
	private static void assertSameTrialRequest(SessionRequest actual, SessionRequest expected)
	{
		requireThat(actual.turnObserver().isPresent(), "actualObserver").isTrue();
		requireThat(expected.turnObserver().isPresent(), "expectedObserver").isTrue();
		if (actual != expected)
			requireThat(actual.turnObserver().orElseThrow() != expected.turnObserver().orElseThrow(), "freshObserver").
				isTrue();
		SessionRequest actualInputs = new SessionRequest(actual.role(), actual.part(), actual.source(), actual.turns(),
			actual.readOnly(), actual.timeLimit(), actual.profile(), actual.files(), actual.plugins(), actual.followUp(),
			actual.controlSource(), actual.extraPermissions());
		SessionRequest expectedInputs = new SessionRequest(expected.role(), expected.part(), expected.source(),
			expected.turns(), expected.readOnly(), expected.timeLimit(), expected.profile(), expected.files(),
			expected.plugins(), expected.followUp(), expected.controlSource(), expected.extraPermissions());
		requireThat(actualInputs, "request").isEqualTo(expectedInputs);
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
	 * Trials of one test case that give the same reply start one judge session and get its verdict, although the
	 * judge would answer every later session the opposite way; a trial whose assertions all have stored verdicts
	 * starts no session and names the trial whose session made them.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void repeatedEvidenceGetsOneVerdict() throws IOException
	{
		// Requirement: R-L10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing(ContractFixtures.responseOnly(ContractFixtures.contract())).window(8).build());
			Repeats repeats = new Repeats(harness);
			int before = developmentOutcomes(harness.campaign());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> developmentOutcomes(state) >= before + 60);
				harness.settle(driver);
			}
			Campaign campaign = harness.campaign();
			requireThat(campaign.development().failures(), "failures").isEmpty();
			requireThat(repeats.sessionsByCase().values().stream().allMatch(count -> count == 1), "oneSessionEach").
				withContext(repeats.sessionsByCase(), "sessionsByCase").isTrue();
			requireThat(repeats.trials().size(), "trials").isGreaterThan(repeats.sessionsByCase().size());
			requireThat(repeats.outcomes(harness, _ -> true).stream().anyMatch(outcome ->
				outcome.verdict() == Verdict.PASS &&
					outcome.rationale().contains("Recorded verdict of development trial ")), "hitNamesItsTrial").isTrue();
			requireThat(harness.store().verdicts(DriverHarness.CAMPAIGN).size(), "records").
				isGreaterThanOrEqualTo(repeats.sessionsByCase().size());
		}
	}

	/**
	 * Two trials that end together with the same evidence get one judge session, started for the trial whose session
	 * ended first, and one verdict.
	 *
	 * @param reversed whether the trial sessions end in reverse order of their start
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "endOrders")
	public void concurrentTrialWaitsForRunningJudge(boolean reversed) throws IOException
	{
		// Requirement: R-L10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing(ContractFixtures.responseOnly(ContractFixtures.contract())).window(16).build());
			Repeats repeats = new Repeats(harness);
			try (Driver driver = harness.open())
			{
				driver.step();
				List<TrialKey> started = runningKeys(harness);
				List<FakeSession> trials = new ArrayList<>(harness.runningTrials());
				if (reversed)
					Collections.reverse(trials);
				Map<String, Path> firstEnded = new HashMap<>();
				for (FakeSession trial : trials)
				{
					harness.end(trial);
					Path effects = repeats.lastResult().effects();
					firstEnded.putIfAbsent(repeats.identity(effects), effects);
				}
				requireThat(firstEnded.size(), "distinctCases").isLessThan(trials.size());
				harness.runUntil(driver, () -> started.stream().allMatch(key ->
					recordedOutcome(harness, key).isPresent()));
				Map<Path, TrialKey> keysByEffects = keysByEffects(harness, started);
				for (VerdictRecord record : harness.store().verdicts(DriverHarness.CAMPAIGN).values())
				{
					String identity = repeats.identityOfTrial(record.trial());
					if (firstEnded.containsKey(identity))
					{
						requireThat(record.trial(), "judgedTrial").
							isEqualTo(Repeats.trialName(keysByEffects.get(firstEnded.get(identity))));
					}
				}
				for (String identity : firstEnded.keySet())
					requireThat(repeats.sessionsByCase().get(identity), "sessions").isEqualTo(1);
				for (TrialKey key : started)
				{
					requireThat(recordedOutcome(harness, key).orElseThrow().verdict(), "verdict").
						isEqualTo(Verdict.PASS);
				}
			}
		}
	}

	/**
	 * Returns whether trial sessions end in reverse order of their start.
	 *
	 * @return the orders
	 */
	@DataProvider
	public static Object[][] endOrders()
	{
		return new Object[][]{{false}, {true}};
	}

	/**
	 * A judge session that fails, or whose output does not match the result schema, writes no verdict: the trial that
	 * waits for it starts its own session, and its valid verdict is the one stored.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void failedJudgeWritesNoRecord() throws IOException
	{
		// Requirement: R-L10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing(ContractFixtures.responseOnly(ContractFixtures.contract())).window(16).build());
			Repeats repeats = new Repeats(harness);
			Set<String> failing = ConcurrentHashMap.newKeySet();
			repeats.failFirst(failing, Driver.JUDGE_ATTEMPTS);
			try (Driver driver = harness.open())
			{
				driver.step();
				Map<String, List<String>> byCase = new LinkedHashMap<>();
				for (LogicalTrial trial : harness.campaign().trials())
				{
					if (trial.state() == TrialState.RUNNING)
					{
						byCase.computeIfAbsent(Repeats.identityOf(trial), _ -> new ArrayList<>()).
							add(Repeats.trialName(trial.key()));
					}
				}
				for (FakeSession trial : new ArrayList<>(harness.runningTrials()))
					harness.end(trial);
				String repeated = byCase.entrySet().stream().filter(entry -> entry.getValue().size() >= 2).
					map(Map.Entry::getKey).findFirst().orElseThrow();
				failing.add(repeated);
				harness.runUntil(driver, () -> repeats.sessions(repeated) > Driver.JUDGE_ATTEMPTS &&
					repeats.outcomes(harness, repeated).size() >= byCase.get(repeated).size() - 1);

				// The sessions that failed stored nothing, so a waiting trial found no verdict and ran its own session
				requireThat(repeats.sessions(repeated), "sessions").isEqualTo(Driver.JUDGE_ATTEMPTS + 1);
				Set<String> recordTrials = new HashSet<>();
				for (VerdictRecord record : harness.store().verdicts(DriverHarness.CAMPAIGN).values())
				{
					if (repeated.equals(repeats.identityOfTrial(record.trial())))
					{
						recordTrials.add(record.trial());
						requireThat(record.verdict().rationale(), "stored").isEqualTo("The first answer.");
					}
				}
				requireThat(recordTrials.size(), "recordTrials").isEqualTo(1);
				requireThat(byCase.get(repeated), "waitingTrials").containsAll(recordTrials);
				for (CampaignEvent.Complete outcome : repeats.outcomes(harness, repeated))
					requireThat(outcome.verdict(), "verdict").isEqualTo(Verdict.PASS);
			}
		}
	}

	/**
	 * Returns the trial that each recorded trial outcome belongs to, by the effects directory of its session.
	 *
	 * @param harness the harness
	 * @param keys    the trials, each of which has a recorded outcome
	 * @return the trials by effects directory
	 */
	private static Map<Path, TrialKey> keysByEffects(DriverHarness harness, List<TrialKey> keys)
	{
		Map<Path, TrialKey> byEffects = new HashMap<>();
		for (TrialKey key : keys)
			byEffects.put(recordedOutcome(harness, key).orElseThrow().files().effects(), key);
		return byEffects;
	}

	/**
	 * Returns the keys of the trials that run.
	 *
	 * @param harness the harness
	 * @return the keys
	 * @throws IOException if the campaign cannot be read
	 */
	private static List<TrialKey> runningKeys(DriverHarness harness) throws IOException
	{
		return harness.campaign().trials().stream().filter(trial -> trial.state() == TrialState.RUNNING).
			map(LogicalTrial::key).toList();
	}

	/**
	 * A later campaign judges again: the stored verdicts of an earlier campaign in the same store are not found.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void laterCampaignJudgesAgain() throws IOException
	{
		// Requirement: R-L10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Campaign campaign = developing(ContractFixtures.responseOnly(ContractFixtures.contract())).window(8).build();
			DriverHarness earlier = new DriverHarness(cleanup.add(Files.createTempDirectory("earlier")), campaign);
			Repeats earlierRepeats = new Repeats(earlier);
			int before = developmentOutcomes(campaign);
			try (Driver driver = earlier.open())
			{
				earlier.runUntil(driver, state -> developmentOutcomes(state) >= before + 30);
			}
			Map<VerdictKey, VerdictRecord> records = earlier.store().verdicts(DriverHarness.CAMPAIGN);
			requireThat(records.isEmpty(), "earlierRecords").isFalse();

			DriverHarness later = new DriverHarness(cleanup.add(Files.createTempDirectory("later")), campaign);
			for (Map.Entry<VerdictKey, VerdictRecord> entry : records.entrySet())
				later.store().writeVerdict("campaign-0", entry.getKey(), entry.getValue());
			Repeats laterRepeats = new Repeats(later);
			try (Driver driver = later.open())
			{
				later.runUntil(driver, state -> developmentOutcomes(state) >= before + 30);
			}
			Set<String> completed = laterRepeats.completedCases(later);
			requireThat(earlierRepeats.sessionsByCase().keySet(), "earlierCases").containsAll(completed);
			requireThat(laterRepeats.sessionsByCase().keySet(), "laterCases").containsAll(completed);
			requireThat(later.store().verdicts(DriverHarness.CAMPAIGN).isEmpty(), "laterRecords").isFalse();
		}
	}

	/**
	 * A trial of each purpose that has a judge takes the verdicts stored for its evidence and writes the verdicts of
	 * its own sessions.
	 *
	 * @param purpose the purpose
	 * @throws IOException if the test directory cannot be used
	 */
	@Test(dataProvider = "recordedPurposes")
	public void everyPurposeTakesAndWritesRecords(Purpose purpose) throws IOException
	{
		// Requirement: R-L10
		Contract contract = ContractFixtures.responseOnly(ContractFixtures.contract());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = switch (purpose)
			{
				case DEVELOPMENT -> new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
					developing(contract).window(8).build());
				case BASELINE -> new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
					new Builder().confirmed(contract, 1).working(1).window(8).build());
				default ->
				{
					Builder start = new Builder().confirmed(contract, 1).working(1).readyBaseline();
					for (String cell : contract.cellIds())
						start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
					yield runningAttemptHarness(cleanup, start.certificationAttempt(1).
						boundaries(Boundaries.paired(contract.cellIds().size())).build());
				}
			};
			Repeats repeats = new Repeats(harness);
			repeats.replyByPurpose();
			String name = EnumNames.lowerCase(purpose) + " trial ";
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> repeats.outcomes(harness, purpose).stream().anyMatch(outcome ->
					outcome.rationale().contains("Recorded verdict of ")) &&
					harness.store().verdicts(DriverHarness.CAMPAIGN).values().stream().
						anyMatch(record -> record.trial().startsWith(name)));
			}
		}
	}

	/**
	 * Targeted trials take and write verdicts as representative trials do: the prompt-author attempts replay one failed
	 * test case, and the attempts after the first take the verdict of the first replay with the same reply.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void targetedTrialsTakeAndWriteRecords() throws IOException
	{
		// Requirement: R-L10
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed(ContractFixtures.responseOnly(ContractFixtures.contract()), 1).working(1).
					readyBaseline().build());
			Set<TrialKey> started = ConcurrentHashMap.newKeySet();
			harness.trials(request ->
			{
				TrialKey key = runningTrial(harness, request).key();
				started.add(key);
				return harness.completed("fail in a " + EnumNames.lowerCase(key.purpose()) + " trial",
					DriverHarness.MODEL);
			});
			harness.judges(request -> harness.judged(request, Verdict.FAIL, "It failed."));
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, () -> harness.store().verdicts(DriverHarness.CAMPAIGN).values().stream().
					anyMatch(record -> record.trial().startsWith("promotion trial ")) &&
					started.stream().filter(key -> key.purpose() == Purpose.PROMOTION).
						map(key -> recordedOutcome(harness, key)).flatMap(Optional::stream).
						anyMatch(outcome -> outcome.rationale().contains("Recorded verdict of promotion trial ")));
			}
		}
	}

	/**
	 * Returns the purposes of trials that a judge decides.
	 *
	 * @return the purposes
	 */
	@DataProvider
	public static Object[][] recordedPurposes()
	{
		return new Object[][]{{Purpose.DEVELOPMENT}, {Purpose.BASELINE}, {Purpose.FORMAL}, {Purpose.CONTROL},
			{Purpose.INCUMBENT}};
	}

	/**
	 * A development trial never finds a held-out verdict: with the verdicts of a certification attempt's trials in
	 * its store, every development trial's verdict comes from a development trial's session.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void developmentNeverFindsHeldOutRecord() throws IOException
	{
		// Requirement: R-L10
		Contract contract = ContractFixtures.responseOnly(ContractFixtures.contract());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Builder start = new Builder().confirmed(contract, 1).working(1).readyBaseline();
			for (String cell : contract.cellIds())
				start.development(cell, CommitGate.MINIMUM_TRIALS, 0);
			DriverHarness attempt = runningAttemptHarness(cleanup, start.certificationAttempt(1).
				boundaries(Boundaries.paired(contract.cellIds().size())).build());
			Repeats attemptRepeats = new Repeats(attempt);
			try (Driver driver = attempt.open())
			{
				// Trials with equal replies share their records, so a few distinct test cases make them all
				attempt.runUntil(driver, () -> attempt.store().verdicts(DriverHarness.CAMPAIGN).size() >= 2);
			}
			Map<VerdictKey, VerdictRecord> heldOut = attempt.store().verdicts(DriverHarness.CAMPAIGN);

			DriverHarness development = new DriverHarness(cleanup.add(Files.createTempDirectory("development")),
				developing(contract).window(8).build());
			for (Map.Entry<VerdictKey, VerdictRecord> entry : heldOut.entrySet())
				development.store().writeVerdict(DriverHarness.CAMPAIGN, entry.getKey(), entry.getValue());
			Repeats repeats = new Repeats(development);
			int before = developmentOutcomes(development.campaign());
			try (Driver driver = development.open())
			{
				development.runUntil(driver, state -> developmentOutcomes(state) >= before + 40);
			}
			requireThat(attemptRepeats.sessionsByCase().isEmpty(), "heldOutSessions").isFalse();
			for (CampaignEvent.Complete outcome : repeats.outcomes(development, _ -> true))
			{
				requireThat(outcome.rationale(), "rationale").doesNotContain("Recorded verdict of formal").
					doesNotContain("Recorded verdict of control").doesNotContain("Recorded verdict of incumbent");
			}
			requireThat(repeats.sessionsByCase().keySet(), "developmentCases").
				doesNotContainAny(attemptRepeats.sessionsByCase().keySet());
		}
	}

	/**
	 * The declared actions assertion added to a mixed-scope test fixture.
	 */
	static final String ACTIONS_ASSERTION = "keeps_project";

	/**
	 * Returns a contract whose tests each declare response and actions assertions.
	 *
	 * @return the mixed-scope contract
	 */
	static Contract withMixedEvidenceScopes()
	{
		Contract response = ContractFixtures.withEvidenceScope(ContractFixtures.contract(), EvidenceScope.RESPONSE);
		Map<String, List<MappedAssertion>> assertions = new LinkedHashMap<>();
		for (PromptTest test : response.tests())
		{
			List<MappedAssertion> mapped = new ArrayList<>(test.assertions());
			mapped.add(new MappedAssertion(new Assertion(ACTIONS_ASSERTION, AssertionKind.GUARD, null,
				OnToolFailure.FAIL, "The trial leaves the project unchanged.", null, EvidenceScope.ACTIONS),
				test.requirementIds().getFirst()));
			assertions.put(test.id(), mapped);
		}
		return ContractFixtures.withAssertions(response, assertions);
	}

	/**
	 * A trial whose test has judged assertions of both scopes is judged in two sessions, one per scope: the
	 * {@code response} session decides only the {@code response} assertions from the response prompt and reads no
	 * file, and the {@code actions} session decides only the {@code actions} assertions and reads the trial's project.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void judgesEachScopeInItsOwnSession() throws IOException
	{
		// Requirement: R-L19
		Contract contract = withMixedEvidenceScopes();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing(contract).window(8).build());
			Map<Path, String> replies = new ConcurrentHashMap<>();
			Map<Path, String> tests = new ConcurrentHashMap<>();
			AtomicInteger trialCount = new AtomicInteger();
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				String reply = "pass number " + trialCount.incrementAndGet();
				SessionResult.Completed result = harness.completed(reply, DriverHarness.MODEL);
				replies.put(result.effects(), reply);
				tests.put(result.effects(), trial.testCase().testId());
				return result;
			});
			harness.judges(request -> harness.judged(request, Verdict.PASS, "The scope holds."));
			int before = developmentOutcomes(harness.campaign());
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> developmentOutcomes(state) >= before + 6);
				harness.settle(driver);
			}
			List<SessionRequest> judges = judgeRequests(harness);
			List<SessionRequest> actions = judges.stream().filter(request -> !request.readOnly().isEmpty()).toList();
			List<SessionRequest> response = judges.stream().filter(request -> request.readOnly().isEmpty()).toList();
			requireThat(actions.isEmpty(), "noActionsSession").isFalse();
			requireThat(response.size(), "responseSessions").isEqualTo(actions.size());
			for (SessionRequest request : actions)
			{
				requireThat(ids(harness, request), "actionsAssertions").isEqualTo(List.of(ACTIONS_ASSERTION));
				requireThat(request.turns().getFirst(), "actionsTurn").startsWith("# Trial Judgment");
			}
			for (SessionRequest request : response)
			{
				requireThat(request.turns().getFirst(), "responseTurn").startsWith("# Reply Judgment");
				requireThat(ids(harness, request), "responseAssertions").doesNotContain(ACTIONS_ASSERTION);
			}
			for (SessionRequest request : actions)
			{
				Path effects = request.readOnly().getFirst();
				String reply = replies.get(effects);
				long responseSessions = response.stream().filter(other ->
					other.turns().getFirst().contains("\"" + reply + "\"")).count();
				requireThat(responseSessions, "responseSessionsOfTrial").withContext(reply, "reply").isEqualTo(1L);
				long actionsSessions = actions.stream().filter(other -> other.readOnly().getFirst().equals(effects)).
					count();
				requireThat(actionsSessions, "actionsSessionsOfTrial").withContext(reply, "reply").isEqualTo(1L);
				PromptTest test = contract.findTest(tests.get(effects)).orElseThrow();
				SessionRequest replyRequest = response.stream().filter(other ->
					other.turns().getFirst().contains("\"" + reply + "\"")).findFirst().orElseThrow();
				List<String> responseIds = test.assertions().stream().map(MappedAssertion::assertion).
				filter(assertion -> assertion.evidenceScope() == EvidenceScope.RESPONSE).map(Assertion::id).toList();
				requireThat(ids(harness, replyRequest), "responseAssertionsOfTrial").isEqualTo(responseIds);
			}
		}
	}

	/**
	 * Trials of one test case whose replies are identical but whose projects and transcripts differ share one
	 * {@code response} session, whose verdicts the store records, and each start their own {@code actions} session,
	 * at once and without waiting for another trial's, whose verdicts the store never records.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void equalRepliesShareOnlyTheResponseSession() throws IOException
	{
		// Requirement: R-L19
		// Requirement: R-L10
		Contract contract = withMixedEvidenceScopes();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("driver")),
				developing(contract).window(16).build());
			Map<Path, String> identities = new ConcurrentHashMap<>();
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				SessionResult.Completed result = harness.passed();
				identities.put(result.effects(), Repeats.identityOf(trial));
				return result;
			});
			harness.judges(request -> harness.judged(request, Verdict.PASS, "The scope holds."));
			Map<String, Integer> trialsByCase = new HashMap<>();
			try (Driver driver = harness.open())
			{
				driver.step();
				List<TrialKey> started = runningKeys(harness);
				List<FakeSession> trials = new ArrayList<>(harness.runningTrials());
				for (FakeSession trial : trials)
					harness.end(trial);
				for (String identity : identities.values())
					trialsByCase.merge(identity, 1, Integer::sum);
				requireThat(trialsByCase.size(), "distinctCases").isLessThan(trials.size());
				driver.step();

				// The judge sessions fill the sessions that the trials ran in, and no two of them judge one test case's
				// replies, while one trial's actions session never waits for another's
				int runningJudges = 0;
				Map<String, Integer> runningActions = new HashMap<>();
				Map<String, Integer> runningResponse = new HashMap<>();
				for (FakeSession session : harness.running())
				{
					SessionRequest request = session.request();
					if (request.role() != SessionRole.JUDGE)
						continue;
					++runningJudges;
					if (request.readOnly().isEmpty())
						runningResponse.merge(Repeats.identityOf(request), 1, Integer::sum);
					else
						runningActions.merge(identities.get(request.readOnly().getFirst()), 1, Integer::sum);
				}
				requireThat(runningJudges, "runningJudges").isEqualTo(trials.size());
				for (Map.Entry<String, Integer> entry : runningResponse.entrySet())
					requireThat(entry.getValue(), "runningResponse").withContext(entry.getKey(), "case").isEqualTo(1);
				requireThat(runningActions.keySet().stream().anyMatch(identity -> runningActions.get(identity) > 1),
					"actionsOfOneCaseAtOnce").isTrue();

				harness.runUntil(driver, () -> started.stream().allMatch(key ->
					recordedOutcome(harness, key).isPresent()));
				for (TrialKey key : started)
				{
					requireThat(recordedOutcome(harness, key).orElseThrow().verdict(), "verdict").
						isEqualTo(Verdict.PASS);
				}
			}
			Map<String, Integer> responseSessions = new HashMap<>();
			Map<String, Integer> actionsSessions = new HashMap<>();
			for (SessionRequest request : judgeRequests(harness))
			{
				if (request.readOnly().isEmpty())
					responseSessions.merge(Repeats.identityOf(request), 1, Integer::sum);
				else
					actionsSessions.merge(identities.get(request.readOnly().getFirst()), 1, Integer::sum);
			}
			for (Map.Entry<String, Integer> entry : actionsSessions.entrySet())
			{
				requireThat(responseSessions.get(entry.getKey()), "responseSessions").
					withContext(entry.getValue(), "actionsSessions").isEqualTo(1);
			}
			for (Map.Entry<String, Integer> entry : trialsByCase.entrySet())
			{
				requireThat(actionsSessions.getOrDefault(entry.getKey(), 0), "actionsSessions").
					withContext(entry.getKey(), "case").isGreaterThanOrEqualTo(entry.getValue());
			}
			Map<VerdictKey, VerdictRecord> records = harness.store().verdicts(DriverHarness.CAMPAIGN);
			requireThat(records.isEmpty(), "records").isFalse();
			for (VerdictRecord record : records.values())
			{
				requireThat(record.verdict().assertion(), "recordedAssertion").isNotEqualTo(ACTIONS_ASSERTION);
			}
		}
	}

	/**
	 * Returns the identifiers of the assertions that a judge request asks the judge to decide.
	 *
	 * @param harness the harness
	 * @param request the request
	 * @return the identifiers, in request order
	 */
	private static List<String> ids(DriverHarness harness, SessionRequest request)
	{
		List<String> ids = new ArrayList<>();
		for (JsonNode assertion : harness.decidedAssertions(request))
			ids.add(assertion.get("id").asString());
		return ids;
	}

	/**
	 * Trials that all reply the same way, and a judge that passes the first session of each test case and fails
	 * every later one.
	 */
	private static final class Repeats
	{
		private static final JsonMapper JSON = JsonMapper.builder().build();
		private final Map<Path, String> identities = new ConcurrentHashMap<>();
		private final Map<TrialKey, LogicalTrial> started = new ConcurrentHashMap<>();
		private final Map<String, Integer> sessions = new ConcurrentHashMap<>();
		private final Map<String, Integer> invalid = new ConcurrentHashMap<>();
		private volatile Set<String> failing = Set.of();
		private volatile int failures;
		private volatile SessionResult.Completed last;
		private volatile boolean byPurpose;

		/**
		 * Scripts the trial and judge sessions of a harness.
		 *
		 * @param harness the harness
		 */
		Repeats(DriverHarness harness)
		{
			harness.trials(request ->
			{
				LogicalTrial trial = runningTrial(harness, request);
				recordStarted(harness);
				SessionResult.Completed result;
				if (byPurpose)
				{
					result = harness.completed("pass in a " + EnumNames.lowerCase(trial.key().purpose()) + " trial",
						DriverHarness.MODEL);
				}
				else
					result = harness.passed();
				identities.put(result.effects(), identityOf(trial));
				last = result;
				return result;
			});
			harness.judges(request ->
			{
				String identity = identityOf(request);
				int count = sessions.merge(identity, 1, Integer::sum);
				if (failing.contains(identity) && invalid.merge(identity, 1, Integer::sum) <= failures)
					return harness.judged("{}");
				if (byPurpose || count == 1 || failing.contains(identity))
					return harness.judged(request, Verdict.PASS, "The first answer.");
				return harness.judged(request, Verdict.FAIL, "An opposite answer.");
			});
		}

		/**
		 * Remembers the trials that run, so that the outcomes of trials that have since left the campaign can be read.
		 *
		 * @param harness the harness
		 */
		private void recordStarted(DriverHarness harness)
		{
			try
			{
				for (LogicalTrial trial : harness.campaign().trials())
				{
					if (trial.state() == TrialState.RUNNING)
						started.putIfAbsent(trial.key(), trial);
				}
			}
			catch (IOException e)
			{
				throw new UncheckedIOException(e);
			}
		}

		/**
		 * Makes each trial's reply name its purpose, so that only trials of one purpose share evidence.
		 */
		void replyByPurpose()
		{
			this.byPurpose = true;
		}

		/**
		 * Makes the first sessions of the given test cases end with output that does not match the result schema.
		 *
		 * @param cases the identities of the test cases
		 * @param count the number of such sessions per test case
		 */
		void failFirst(Set<String> cases, int count)
		{
			this.failing = cases;
			this.failures = count;
		}

		/**
		 * Returns the result of the trial session that ended last.
		 *
		 * @return the result
		 */
		SessionResult.Completed lastResult()
		{
			return last;
		}

		/**
		 * Returns the test case of a trial.
		 *
		 * @param effects the trial's effects directory
		 * @return the test's identifier and the test case
		 */
		String identity(Path effects)
		{
			return identities.get(effects);
		}

		/**
		 * Returns the test case of a trial that a verdict record names.
		 *
		 * @param trialName the name of the trial in the record
		 * @return the test's identifier and the test case, or {@code null} if no such trial ran
		 */
		String identityOfTrial(String trialName)
		{
			for (LogicalTrial trial : started.values())
			{
				if (trialName(trial.key()).equals(trialName))
					return identityOf(trial);
			}
			return null;
		}

		/**
		 * Returns the number of judge sessions of each test case.
		 *
		 * @return the sessions by test case
		 */
		Map<String, Integer> sessionsByCase()
		{
			return Map.copyOf(sessions);
		}

		/**
		 * Returns the number of judge sessions of one test case.
		 *
		 * @param identity the test case's identity
		 * @return the number of sessions
		 */
		int sessions(String identity)
		{
			return sessions.getOrDefault(identity, 0);
		}

		/**
		 * Returns the name under which a verdict record names a trial.
		 *
		 * @param key the trial's key
		 * @return the name
		 */
		static String trialName(TrialKey key)
		{
			return EnumNames.lowerCase(key.purpose()) + " trial " + key.sequence() + " of cell " + key.cell() +
				", series " + key.series();
		}

		/**
		 * Returns the test case of each trial.
		 *
		 * @return the test cases by effects directory
		 */
		Map<Path, String> trials()
		{
			return Map.copyOf(identities);
		}

		/**
		 * Returns the identity of a trial's test case.
		 *
		 * @param trial the trial
		 * @return the test and the test case
		 */
		static String identityOf(LogicalTrial trial)
		{
			ArrayNode values = JSON.createArrayNode();
			TestValueJson.write(trial.testCase().testCase().values(), values);
			return identity(trial.testCase().testId(), trial.testCase().turns(), values);
		}

		/**
		 * Returns the identity of the test case that a judge request shows.
		 *
		 * @param request the judge session's request
		 * @return the test and the test case
		 */
		static String identityOf(SessionRequest request)
		{
			String turn = request.turns().getFirst();
			String section;
			if (request.readOnly().isEmpty())
				section = "### Replies";
			else
				section = "### Evidence";
			int start = turn.indexOf("\n\n{", turn.indexOf(section)) + 2;
			int end = turn.indexOf("\n\nThe trial agent's reply", start);
			JsonNode testCase = JSON.readTree(turn.substring(start, end));
			List<String> turns = new ArrayList<>();
			for (JsonNode text : testCase.get("turns"))
				turns.add(text.asString());
			return identity(testCase.get("test").asString(), turns, testCase.get("values"));
		}

		/**
		 * Returns the identity of a test case.
		 *
		 * @param test            the test's identifier
		 * @param turns           the turns that its trials receive
		 * @param values          its variable values
		 * @return the identity
		 */
		private static String identity(String test, List<String> turns, JsonNode values)
		{
			return test + "\n" + String.join("\n", turns) + "\n" + values;
		}

		/**
		 * Returns the identities of the test cases whose trials have outcomes.
		 *
		 * @param harness the harness
		 * @return the identities
		 */
		Set<String> completedCases(DriverHarness harness)
		{
			Set<String> cases = new HashSet<>();
			for (LogicalTrial trial : started.values())
			{
				if (recordedOutcome(harness, trial.key()).isPresent())
					cases.add(identityOf(trial));
			}
			return cases;
		}

		/**
		 * Returns the recorded outcomes of the trials that match a condition.
		 *
		 * @param harness   the harness
		 * @param condition selects the trials
		 * @return the outcomes
		 */
		List<CampaignEvent.Complete> outcomes(DriverHarness harness, Predicate<LogicalTrial> condition)
		{
			List<CampaignEvent.Complete> outcomes = new ArrayList<>();
			for (LogicalTrial trial : started.values())
			{
				if (condition.test(trial))
					recordedOutcome(harness, trial.key()).ifPresent(outcomes::add);
			}
			return outcomes;
		}

		/**
		 * Returns the recorded outcomes of the trials of one purpose.
		 *
		 * @param harness the harness
		 * @param purpose the purpose
		 * @return the outcomes
		 */
		List<CampaignEvent.Complete> outcomes(DriverHarness harness, Purpose purpose)
		{
			return outcomes(harness, trial -> trial.key().purpose() == purpose);
		}

		/**
		 * Returns the recorded outcomes of the trials of one test case.
		 *
		 * @param harness  the harness
		 * @param identity the test case's identity
		 * @return the outcomes
		 */
		List<CampaignEvent.Complete> outcomes(DriverHarness harness, String identity)
		{
			return outcomes(harness, trial -> identityOf(trial).equals(identity));
		}
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
	 * Returns delivery results that stop before the delivery completes, one of each kind.
	 *
	 * @return the results
	 */
	@DataProvider
	public static Object[][] stoppedDeliveries()
	{
		RegularFile file = new RegularFile(Sha256.of("fork".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"));
		return new Object[][]
		{
			{new DeliveryResult.UnownedChange(List.of(new PathChange.Added(Path.of("notes.md"), file)))},
			{new DeliveryResult.Conflicted(List.of(new DeliveryConflict(DeliveryConflict.Kind.BOTH_CHANGED,
				DriverHarness.PROMPT, DriverHarness.PROMPT)))},
			{new DeliveryResult.ConcurrentEdit(new DeliveryChange.Delete(0, DriverHarness.PROMPT, file), 0)},
			{new DeliveryResult.VerificationFailed(List.of(DriverHarness.PROMPT))}
		};
	}

	/**
	 * Verifies that a slice whose delivery stopped reports the campaign as waiting for the user, never as delivered, and
	 * describes the stop instead of a revision that waits.
	 *
	 * @param stopped the delivery's result
	 */
	@Test(dataProvider = "stoppedDeliveries")
	public void stoppedDeliveryAwaitsUser(DeliveryResult stopped)
	{
		StatusReport report = StatusReport.of(new Builder().confirmed().working(1).build(), ReportEvidence.NONE);
		StatusReport slice = SliceReports.ofSlice(report, stopped, true);
		requireThat(slice.status(), "status").isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
		requireThat(slice.pending(), "pending").isEqualTo(SliceReports.describe(stopped));
	}

	/**
	 * Verifies that a slice without a stopped delivery waits for the user only while a revision waits for
	 * confirmation.
	 */
	@Test
	public void revisionPendingAwaitsUser()
	{
		StatusReport report = StatusReport.of(new Builder().confirmed().working(1).build(), ReportEvidence.NONE);
		StatusReport waiting = SliceReports.ofSlice(report, null, true);
		requireThat(waiting.status(), "status").isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
		requireThat(waiting.pending(), "pending").isEqualTo(StatusReport.REVISION_PENDING);
		requireThat(SliceReports.ofSlice(report, null, false), "unchanged").isEqualTo(report);
	}

	/**
	 * Verifies that a slice whose delivery stops, here because the user edited a file that the candidate also edits,
	 * reports the campaign as waiting for the user and describes the stop.
	 *
	 * @throws IOException if the test directory cannot be used
	 */
	@Test
	public void deliveryStopsForUserDirection() throws IOException
	{
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Candidate working = new Candidate(1, Builder.snapshot("candidate-1"));
			DriverHarness harness = deliveringHarness(cleanup.add(Files.createTempDirectory("driver")),
				new Builder().confirmed().working(1).readyDevelopment().readyBaseline().
					certificationAttempt(1).cell("formatting", 100, 0).cell("safety", 100, 0).
					outcome(Outcome.CERTIFIED).campaign().lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).
					delivery(new DeliveryRequest(working, 1, Outcome.CERTIFIED)).build());
			try (Driver driver = harness.open())
			{
				Files.writeString(harness.base().resolve(DriverHarness.PROMPT), "edited by the user", UTF_8);
				SliceResult result = harness.runSlice(driver);
				requireThat(result.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Conflicted.class);
				requireThat(result.status().status(), "status").
					isEqualTo(new ReportStatus.Active(Status.AWAITING_USER));
				requireThat(result.status().pending(), "pending").
					isEqualTo(SliceReports.describe(result.delivery().result()));
			}
		}
	}
	/**
	 * A background driver admits long trials, ignores unrelated host interrupts, and stops only on its addressed
	 * cancellation request, retaining the same logical trials for resumption.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void backgroundIgnoresSliceAndInterrupt() throws IOException
	{
		Contract fixture = ContractFixtures.contract();
		List<PromptTest> tests = fixture.tests().stream().map(test -> new PromptTest(test.id(), test.riskRank(),
			test.requirementIds(), test.absentTriggers(), test.applicability(), test.turns(), Duration.ofMinutes(12),
			test.expectedBehaviour(), test.uncoveredInteractions(), test.variables(), test.committedTestCases(),
			test.testCases(), test.assertions(), test.regressionScenarios(), List.of())).toList();
		Contract contract = new Contract(fixture.requirements(), tests, fixture.profiles(), fixture.judge(),
			fixture.limitations(), fixture.minimumPassRate(), fixture.salt());
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			DriverHarness harness = new DriverHarness(cleanup.add(Files.createTempDirectory("background-driver")),
				new Builder().confirmed(contract, 1).working(1).readyBaseline().window(2).build());
			harness.settings(SliceSettings.DEFAULT.withoutDeadline());
			try (Driver driver = harness.open();
				BackgroundDriver _ =
					new BackgroundDriver(harness.context().lockFile(),
						harness.clock().instant(), harness.context().json(),
						Yielding.fileReplacer()))
			{
				requireThat(driver.step(), "firststep").isInstanceOf(Step.Continue.class);
				List<FakeSession> started = harness.sessions().sessions();
				requireThat(started.size(), "longtrials").isEqualTo(2);
				harness.clock().advance(Duration.ofDays(2));
				harness.interrupts().interrupt(harness.clock().instant());
				requireThat(driver.step(), "unrelatedinterrupt").isInstanceOf(Step.Continue.class);
				for (FakeSession session : started)
					requireThat(session.isInterruptRequested(), "stillrunning").isFalse();
				BackgroundDriver.Owner identity = BackgroundDriver.recorded(
					harness.context().lockFile(), harness.context().json()).orElseThrow();
				BackgroundDriver.requestStop(harness.context().lockFile(),
					identity, Yielding.fileReplacer());
				requireThat(driver.step(), "cancellationgrace").isInstanceOf(Step.Continue.class);
				harness.clock().advance(SliceSettings.DEFAULT.grace());
				requireThat(driver.step(), "cancelledstep").isInstanceOf(Step.Stopped.class);
				requireThat(harness.campaign().header().lifecycle(), "cancelled").
					isEqualTo(Lifecycle.CANCELLED);
				requireThat(harness.campaign().header().lifecycle().isResumable(), "resumable").isTrue();
				requireThat(harness.sessions().sessions().size(), "nomoreadmissions").isEqualTo(2);
				for (FakeSession session : started)
					requireThat(session.isInterruptRequested(), "stoppedsession").isTrue();
			}
		}
	}
}
