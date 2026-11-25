package com.holdmyspot.cat.common.optimizer.execution.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.TemporaryDirectories;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationHistory;
import com.holdmyspot.cat.common.optimizer.campaign.CertificationRecord;
import com.holdmyspot.cat.common.optimizer.campaign.DecisionTable;
import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.campaign.Header;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementCondition;
import com.holdmyspot.cat.common.optimizer.campaign.MeasurementIdentity;
import com.holdmyspot.cat.common.optimizer.campaign.Outcome;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.TrialState;
import com.holdmyspot.cat.common.optimizer.campaign.test.Boundaries;
import com.holdmyspot.cat.common.optimizer.campaign.test.Builder;
import com.holdmyspot.cat.common.optimizer.contract.Contract;
import com.holdmyspot.cat.common.optimizer.contract.PromptTest;
import com.holdmyspot.cat.common.optimizer.contract.PromptTestLocations;
import com.holdmyspot.cat.common.optimizer.contract.Requirement;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfiles;
import com.holdmyspot.cat.common.optimizer.contract.test.ContractFixtures;
import com.holdmyspot.cat.common.optimizer.delivery.DeliveryResult;
import com.holdmyspot.cat.common.optimizer.evidence.SessionEvidence;
import com.holdmyspot.cat.common.optimizer.execution.Driver;
import com.holdmyspot.cat.common.optimizer.execution.DriverContext;
import com.holdmyspot.cat.common.optimizer.execution.Reconciler;
import com.holdmyspot.cat.common.optimizer.execution.SliceResult;
import com.holdmyspot.cat.common.optimizer.inventory.InventoryScanner;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.optimizer.port.SessionHandle;
import com.holdmyspot.cat.common.optimizer.port.SessionPort;
import com.holdmyspot.cat.common.optimizer.port.SessionRequest;
import com.holdmyspot.cat.common.optimizer.port.test.FakeSessionPort;
import com.holdmyspot.cat.common.optimizer.statistics.DecisionBoundaries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.testng.annotations.Test;

/**
 * Tests a shared certification and its delivery through the driver's evaluator ports.
 */
public final class SharedCertificationTest
{
	private static final String CODEX_CELL = "codex:safety";
	private static final String CLAUDE_CELL = "claude:safety";
	private static final Map<String, Map<MeasurementCondition, String>> LOADER_CONDITIONS = Map.of(
		CODEX_CELL, Map.of(MeasurementCondition.PROVIDER, "codex",
			MeasurementCondition.LOADER_CONFIGURATION, "codex-loader"),
		CLAUDE_CELL, Map.of(MeasurementCondition.PROVIDER, "claude",
			MeasurementCondition.LOADER_CONFIGURATION, "claude-loader"));

	/**
	 * Creates the test.
	 */
	public SharedCertificationTest()
	{
	}

	/**
	 * Reopening after one evaluator passes preserves that decision and queues only the unfinished trial. The driver
	 * appends one certification after both evaluators pass the same frozen candidate.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void deliversOnlyAfterBothEvaluatorsPass() throws IOException
	{
		// Requirement: R-L9
		Campaign initial = onePassShort();
		Sha256 snapshot = initial.attempt(1).candidate().snapshot();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("shared-certificate-"));
			Path project = Files.createDirectory(root.resolve("base"));
			Files.writeString(project.resolve(DriverHarness.PROMPT), "fork", UTF_8);
			DriverHarness harness = new DriverHarness(root.resolve("driver"), initial,
				InventoryScanner.scan(project, Set.of()), project);
			List<Sha256> codex = new ArrayList<>();
			List<Sha256> claude = new ArrayList<>();
			DriverContext context = harness.context(harness.sessions(), Map.of(Harness.CODEX, evaluator(harness, codex),
				Harness.CLAUDE, evaluator(harness, claude)), LOADER_CONDITIONS);
			CertificationAttempt partialAttempt;
			TrialKey interrupted;
			try (Driver driver = Driver.open(context, initial.header().id()).orElseThrow())
			{
				driver.step();
				harness.runningTrials().getFirst().end(harness.passed());
				harness.settle(driver);

				Campaign partial = harness.campaign();
				partialAttempt = partial.attempt(1);
				requireThat(partialAttempt.isRunning(), "partialAttemptRunning").isTrue();
				List<DecisionTable.CellDecision> decisions = List.of(
					DecisionTable.cellDecision(partialAttempt, CODEX_CELL),
					DecisionTable.cellDecision(partialAttempt, CLAUDE_CELL));
				requireThat(decisions, "cellDecisions").contains(DecisionTable.CellDecision.CERTIFIED).
					contains(DecisionTable.CellDecision.UNDECIDED);
				Path history = project.resolve(PromptTestLocations.certificationHistory(DriverHarness.PROMPT));
				requireThat(Files.exists(history), "historyExists").isFalse();
				interrupted = partial.trials().stream().filter(trial -> trial.state() == TrialState.RUNNING).
					findFirst().orElseThrow().key();
				requireThat(codex, "firstCodexSnapshot").isEqualTo(List.of(snapshot));
				requireThat(claude, "firstClaudeSnapshot").isEqualTo(List.of(snapshot));
			}

			try (Driver driver = Driver.open(context, initial.header().id()).orElseThrow())
			{
				Campaign reopened = harness.campaign();
				requireThat(reopened.attempt(1), "reopenedAttempt").isEqualTo(partialAttempt);
				requireThat(Reconciler.find(reopened, interrupted).orElseThrow().state(), "reopenedTrial").
					isEqualTo(TrialState.ASSIGNED);
				driver.step();
				requireThat(Reconciler.find(harness.campaign(), interrupted).orElseThrow().state(), "retriedTrial").
					isEqualTo(TrialState.RUNNING);
				SliceResult finished = harness.runSlice(driver);
				List<Sha256> retriedSnapshots = codex;
				List<Sha256> completedSnapshots = claude;
				if (interrupted.cell().equals(CLAUDE_CELL))
				{
					retriedSnapshots = claude;
					completedSnapshots = codex;
				}
				requireThat(retriedSnapshots, "retriedSnapshots").isEqualTo(List.of(snapshot, snapshot));
				requireThat(completedSnapshots, "completedSnapshots").isEqualTo(List.of(snapshot));
				requireThat(harness.campaign().attempts().size(), "attempts").isEqualTo(1);
				requireThat(finished.delivery().result(), "delivery").isInstanceOf(DeliveryResult.Delivered.class);
				requireThat(harness.campaign().outcome(), "outcome").isEqualTo(Outcome.CERTIFIED);
				CertificationHistory delivered = harness.deliveredHistory();
				requireThat(delivered.entries().size(), "historySize").isEqualTo(1);
				CertificationRecord record = delivered.records().getFirst();
				requireThat(record.snapshot(), "snapshot").isEqualTo(Optional.of(snapshot));
				requireThat(record.evaluatorCells(), "measuredCells").
					isEqualTo(Map.of("claude", List.of(CLAUDE_CELL), "codex", List.of(CODEX_CELL)));
				requireThat(record.identity().valueOf(CODEX_CELL, MeasurementCondition.LOADER_CONFIGURATION),
					"codexLoader").isEqualTo("codex-loader");
				requireThat(record.identity().valueOf(CLAUDE_CELL, MeasurementCondition.LOADER_CONFIGURATION),
					"claudeLoader").isEqualTo("claude-loader");
			}
		}
	}

	/**
	 * Formal failures in one evaluator end the joint attempt uncertified although the other evaluator's cell has already
	 * certified the same frozen candidate, and nothing is delivered or certified.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void oneEvaluatorBelowMinimumBlocksDelivery() throws IOException
	{
		// Requirement: R-L9
		DecisionBoundaries boundaries = Boundaries.standard(2);
		Campaign initial = attempt(boundaries, certifyingPasses(boundaries), 0);
		Sha256 snapshot = initial.attempt(1).candidate().snapshot();
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("shared-certificate-"));
			Path project = Files.createDirectory(root.resolve("base"));
			Files.writeString(project.resolve(DriverHarness.PROMPT), "fork", UTF_8);
			DriverHarness harness = new DriverHarness(root.resolve("driver"), initial,
				InventoryScanner.scan(project, Set.of()), project);
			List<Sha256> codex = new ArrayList<>();
			List<Sha256> claude = new ArrayList<>();
			Set<SessionRequest> claudeRequests = Collections.newSetFromMap(new IdentityHashMap<>());
			harness.trials(request ->
			{
				if (claudeRequests.contains(request))
					return harness.failed();
				return harness.passed();
			});
			DriverContext context = harness.context(harness.sessions(), Map.of(Harness.CODEX, evaluator(harness, codex),
				Harness.CLAUDE, evaluator(harness, claude, claudeRequests)), LOADER_CONDITIONS);
			Campaign ended;
			try (Driver driver = Driver.open(context, initial.header().id()).orElseThrow())
			{
				ended = harness.runUntil(driver, state -> !state.attempt(1).isRunning());
			}

			CertificationAttempt attempt = ended.attempt(1);
			requireThat(DecisionTable.cellDecision(attempt, CODEX_CELL), "codexDecision").
				isEqualTo(DecisionTable.CellDecision.CERTIFIED);
			requireThat(Set.of(DecisionTable.CellDecision.CLEARLY_BELOW, DecisionTable.CellDecision.OUT_OF_REACH),
				"uncertifiableDecisions").contains(DecisionTable.cellDecision(attempt, CLAUDE_CELL));
			requireThat(Set.of(Outcome.BELOW_MINIMUM, Outcome.INCONCLUSIVE), "uncertifiedOutcomes").
				contains(attempt.outcome());
			requireThat(attempt.count(CLAUDE_CELL).passes(), "claudePasses").isEqualTo(0);
			requireThat(claude, "claudeSnapshots").isNotEmpty();
			for (Sha256 measured : claude)
				requireThat(measured, "claudeSnapshot").isEqualTo(snapshot);
			requireThat(codex, "codexSnapshots").isEmpty();
			requireThat(ended.outcome(), "campaignOutcome").isNotEqualTo(Outcome.CERTIFIED);
			requireThat(ended.attempts().size(), "attempts").isEqualTo(1);
			Path history = project.resolve(PromptTestLocations.certificationHistory(DriverHarness.PROMPT));
			requireThat(Files.exists(history), "historyExists").isFalse();
			requireThat(Files.readString(project.resolve(DriverHarness.PROMPT), UTF_8), "deliveredPrompt").
				isEqualTo("fork");
		}
	}

	/**
	 * Returns the fewest passes that certify a cell with no failures.
	 *
	 * @param boundaries the attempt's decision boundaries
	 * @return the passes
	 */
	private static int certifyingPasses(DecisionBoundaries boundaries)
	{
		int passes = 1;
		while (!boundaries.certifies(passes, 0))
			++passes;
		return passes;
	}

	/**
	 * Returns a two-evaluator attempt that needs one more formal pass per cell.
	 *
	 * @return the campaign
	 */
	private static Campaign onePassShort()
	{
		DecisionBoundaries boundaries = Boundaries.standard(2);
		int passes = certifyingPasses(boundaries);
		return attempt(boundaries, passes - 1, passes - 1);
	}

	/**
	 * Returns a running two-evaluator attempt on one frozen candidate with no formal failures.
	 *
	 * @param boundaries   the attempt's decision boundaries
	 * @param codexPasses  the Codex cell's adopted formal passes
	 * @param claudePasses the Claude Code cell's adopted formal passes
	 * @return the campaign
	 */
	private static Campaign attempt(DecisionBoundaries boundaries, int codexPasses, int claudePasses)
	{
		Contract fixture = ContractFixtures.contract();
		List<PromptTest> tests = List.of(fixture.testOf(fixture.findCell("safety").orElseThrow()));
		List<Requirement> requirements = fixture.requirements().stream().
			filter(requirement -> requirement.id().equals("r2")).toList();
		TrialProfiles local = TrialProfiles.compute(fixture.profiles().inventory(), fixture.profiles().ranking(),
			List.of(), List.of(), List.of(), requirements, tests);
		TrialProfiles shared = TrialProfiles.forEvaluators(Map.of(Harness.CODEX, local, Harness.CLAUDE, local));
		Contract contract = new Contract(requirements, tests, shared, fixture.judge(), fixture.limitations(),
			fixture.minimumPassRate(), fixture.salt());
		Header old = new Builder().build().header();
		Header header = new Header(old.id(), old.root(), old.target(), Harness.CODEX, old.lifecycle(), old.goal(),
			old.extraPermissions(), Set.of(Harness.CODEX, Harness.CLAUDE));
		return new Builder().header(header).confirmed(contract, 1).working(1).readyBaseline().
			ceiling(1).window(1).identity(new MeasurementIdentity(Map.of(), LOADER_CONDITIONS)).
			certificationAttempt(1).boundaries(boundaries).
			cell(CODEX_CELL, codexPasses, 0).cell(CLAUDE_CELL, claudePasses, 0).build();
	}

	/**
	 * Records the staged snapshot of requests routed to an evaluator.
	 *
	 * @param harness the driver fixture
	 * @param snapshots the snapshots recorded in this evaluator
	 * @return the evaluator's fake session port
	 */
	private static SessionPort evaluator(DriverHarness harness, List<Sha256> snapshots)
	{
		return evaluator(harness, snapshots, Collections.newSetFromMap(new IdentityHashMap<>()));
	}

	/**
	 * Records the staged snapshot and the request of each session routed to an evaluator.
	 *
	 * @param harness the driver fixture
	 * @param snapshots the snapshots recorded in this evaluator
	 * @param requests the requests recorded in this evaluator
	 * @return the evaluator's fake session port
	 */
	private static SessionPort evaluator(DriverHarness harness, List<Sha256> snapshots, Set<SessionRequest> requests)
	{
		return new FakeSessionPort()
		{
			@Override
			public SessionHandle start(SessionRequest request) throws IOException
			{
				requests.add(request);
				snapshots.add(harness.snapshot(request));
				return harness.sessions().start(request);
			}

			@Override
			public SessionEvidence readEvidence(Path transcript)
			{
				return harness.sessions().readEvidence(transcript);
			}
		};
	}
}
