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
	 * Returns a two-evaluator attempt that needs one more formal pass per cell.
	 *
	 * @return the campaign
	 */
	private static Campaign onePassShort()
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
		DecisionBoundaries boundaries = Boundaries.standard(2);
		int passes = 1;
		while (!boundaries.certifies(passes, 0))
			++passes;
		return new Builder().header(header).confirmed(contract, 1).working(1).readyBaseline().
			ceiling(1).window(1).identity(new MeasurementIdentity(Map.of(), LOADER_CONDITIONS)).
			certificationAttempt(1).boundaries(boundaries).
			cell(CODEX_CELL, passes - 1, 0).cell(CLAUDE_CELL, passes - 1, 0).build();
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
		return new FakeSessionPort()
		{
			@Override
			public SessionHandle start(SessionRequest request) throws IOException
			{
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
