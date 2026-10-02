package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.execution.Driver;
import io.github.cowwoc.cat.common.optimizer.execution.DriverHarness;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.campaign.CategoryCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.AttemptResult;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.campaign.OptimizationEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialInputs;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.statistics.ConfidenceBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.Interval;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.time.Duration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link StatusReport}. Every campaign state comes from {@link CampaignBuilder}; hand-computed values are shown
 * in comments.
 */
public final class StatusReportTest
{
	/**
	 * Every retained round attempt reports its passing and total promotion runs, acceptance, and winner.
	 */
	@Test
	public void reportsEachAttemptAndWinner()
	{
		// Requirement: R-D12
		TrialInstance trigger = CampaignBuilder.targeted("safety", "cache");
		List<PromotionRun> passed = List.of(
			new PromotionRun(PromotionRun.Role.TRIGGERING, trigger, new TrialKey(Purpose.PROMOTION, 1,
				CampaignBuilder.snapshot("author-1"), "safety", 1), Verdict.PASS),
			new PromotionRun(PromotionRun.Role.TRIGGERING, trigger, new TrialKey(Purpose.PROMOTION, 1,
				CampaignBuilder.snapshot("author-1"), "safety", 2), Verdict.PASS));
		List<PromotionRun> failed = List.of(
			new PromotionRun(PromotionRun.Role.TRIGGERING, trigger, new TrialKey(Purpose.PROMOTION, 2,
				CampaignBuilder.snapshot("author-2"), "safety", 1), Verdict.PASS),
			new PromotionRun(PromotionRun.Role.TRIGGERING, trigger, new TrialKey(Purpose.PROMOTION, 2,
				CampaignBuilder.snapshot("author-2"), "safety", 2), Verdict.FAIL));
		Sha256 working = CampaignBuilder.snapshot("candidate-1");
		List<AttemptResult> results = List.of(
			new AttemptResult(1, working, 1, "safety", CampaignBuilder.snapshot("author-1"), passed, 4, true, true),
			new AttemptResult(1, working, 2, "safety", CampaignBuilder.snapshot("author-2"), failed, 2, false, false));
		OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(), results);
		StatusReport report = StatusReport.of(new CampaignBuilder().confirmed().working(1).
			optimization(evidence).build(), ReportEvidence.NONE);
		JsonNode attempts = report.toJson(JsonMapper.builder().build()).get("author_attempts");

		requireThat(attempts, "attempts").isNotNull();
		requireThat(attempts.size(), "attempts").isEqualTo(2);
		requireThat(attempts.get(0).get("round").asInt(), "round").isEqualTo(1);
		requireThat(attempts.get(0).get("number").asInt(), "attemptNumber").isEqualTo(1);
		requireThat(attempts.get(0).get("passing_runs").asInt(), "passingRuns").isEqualTo(2);
		requireThat(attempts.get(0).get("total_runs").asInt(), "totalRuns").isEqualTo(2);
		requireThat(attempts.get(0).get("accepted").asBoolean(), "accepted").isTrue();
		requireThat(attempts.get(0).get("winner").asBoolean(), "winner").isTrue();
		requireThat(attempts.get(1).get("passing_runs").asInt(), "passingRuns").isEqualTo(1);
		requireThat(attempts.get(1).get("total_runs").asInt(), "totalRuns").isEqualTo(2);
		requireThat(attempts.get(1).get("accepted").asBoolean(), "accepted").isFalse();
		requireThat(attempts.get(1).get("winner").asBoolean(), "winner").isFalse();
		requireThat(report.summary(), "summary").contains("Attempt 1").contains("2/2").
			contains("accepted: true").contains("winner: true").contains("Attempt 2").contains("1/2").
			contains("accepted: false").contains("winner: false");
	}

	/**
	 * A reserved attempt appears before its author returns, with acceptance still pending.
	 */
	@Test
	public void reportsPendingAttempt()
	{
		// Requirement: R-D12
		Campaign campaign = new CampaignBuilder().confirmed().working(1).
			round(new Round(Map.of("safety", 0), true, Map.of(1, "safety"), List.of())).build();
		JsonNode attempts = StatusReport.of(campaign, ReportEvidence.NONE).
			toJson(JsonMapper.builder().build()).get("author_attempts");
		requireThat(attempts, "attempts").isNotNull();
		requireThat(attempts.size(), "attempts").isEqualTo(1);
		requireThat(attempts.get(0).get("number").asInt(), "attemptNumber").isEqualTo(1);
		requireThat(attempts.get(0).get("passing_runs").asInt(), "passingRuns").isEqualTo(0);
		requireThat(attempts.get(0).get("total_runs").asInt(), "totalRuns").isEqualTo(0);
		requireThat(attempts.get(0).get("accepted").isNull(), "acceptancePending").isTrue();
		requireThat(attempts.get(0).get("snapshot").isNull(), "authorPending").isTrue();
		requireThat(attempts.get(0).get("winner").asBoolean(), "winner").isFalse();
	}


	/**
	 * The tolerance for floating-point comparisons.
	 */
	private static final double EPSILON = 1e-12;

	/**
	 * Creates the test.
	 */
	public StatusReportTest()
	{
	}

	/**
	 * Verifies that missing prompt measurements and trial token comparisons remain explicit in status.
	 */
	@Test
	public void explainsUnavailablePromptSizes()
	{
		// Requirement: R-E18
		Campaign campaign = new CampaignBuilder().confirmed().working(1).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		ObjectNode output = report.toJson(JsonMapper.builder().build());
		requireThat(output.has("prompt_size"), "promptSize").isTrue();
		requireThat(output.get("prompt_size").get("working").get("unavailable").asString(), "reason").isNotBlank();
		requireThat(output.get("prompt_size").get("incumbent").get("unavailable").asString(), "reason").isNotBlank();
		requireThat(output.has("trial_input_tokens"), "trialTokens").isTrue();
		for (JsonNode cell : output.get("trial_input_tokens"))
		{
			requireThat(cell.get("working_median").isNull(), "workingUnavailable").isTrue();
			requireThat(cell.get("incumbent_median").isNull(), "incumbentUnavailable").isTrue();
			requireThat(cell.get("unavailable").asString(), "reason").isNotBlank();
		}
	}

	/**
	 * Verifies measured snapshot sizes and per-cell trial input tokens through the driver.
	 *
	 * @throws IOException if the fixture cannot be used
	 */
	@Test
	public void reportsSnapshotSizesAndTrialTokens() throws IOException
	{
		// Requirement: R-E18
		try (TemporaryDirectories cleanup = new TemporaryDirectories())
		{
			Path root = cleanup.add(Files.createTempDirectory("prompt-sizes"));
			Path project = Files.createDirectories(root.resolve("project"));
			Files.writeString(project.resolve(DriverHarness.PROMPT), "fork", UTF_8);
			FileInventory fork = new FileInventory(project, Map.of(DriverHarness.PROMPT,
				new RegularFile(Sha256.of("fork".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"))));
			DriverHarness harness = new DriverHarness(root.resolve("driver"),
				new CampaignBuilder().confirmed().working(1).build(), fork, project);
			Sha256 working = CampaignBuilder.snapshot("candidate-1");
			harness.overlays().put(working, harness.candidate("short prompt\n"));
			harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
			harness.trials(request ->
			{
				SessionResult.Completed trial = harness.passed();
				long input = 30;
				if (harness.snapshot(request).equals(CampaignBuilder.INCUMBENT))
					input = 70;
				return new SessionResult.Completed(trial.transcript(), trial.responses(), trial.effects(), trial.model(),
					trial.reasoning(), new TokenUsage(input, 10, 1, 0), trial.windows(),
					trial.promptSources(), trial.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.baseline().count("safety").outcomes() > 0 &&
					state.baseline().count("formatting").outcomes() > 0 &&
					state.development().counts().getOrDefault("safety", CategoryCounts.EMPTY).outcomes() > 0 &&
					state.development().counts().getOrDefault("formatting", CategoryCounts.EMPTY).outcomes() > 0);
				harness.clock().set(harness.clock().instant().plus(Duration.ofHours(1)));
				StatusReport report = harness.runSlice(driver).status();
				ObjectNode output = report.toJson(JsonMapper.builder().build());
				requireThat(output.has("prompt_size"), "promptSize").isTrue();
				requireThat(output.get("prompt_size").get("working").get("bytes").asLong(), "workingBytes").isEqualTo(13);
				requireThat(output.get("prompt_size").get("working").get("words").asLong(), "workingWords").isEqualTo(2);
				requireThat(output.get("prompt_size").get("incumbent").get("bytes").asLong(), "incumbentBytes").isEqualTo(4);
				requireThat(output.get("prompt_size").get("incumbent").get("words").asLong(), "incumbentWords").isEqualTo(1);
				for (JsonNode cell : output.get("trial_input_tokens"))
				{
					requireThat(cell.get("working_median").asLong(), "workingMedian").isEqualTo(30);
					requireThat(cell.get("incumbent_median").asLong(), "incumbentMedian").isEqualTo(70);
				}
				requireThat(output.get("trial_input_tokens").size(), "cells").isEqualTo(2);
				requireThat(report.summary(), "summary").contains("13 bytes").contains("30").contains("70");
			}
		}
	}

	/**
	 * Verifies that medians use observed samples, with zero distinguished from missing usage.
	 */
	@Test
	public void reportsNearestRankAndZeroUsage()
	{
		// Requirement: R-E18
		OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(
			CampaignBuilder.snapshot("candidate-1"), Map.of("safety", new TrialInputs(5, List.of(100L, 10L, 50L, 30L))),
			CampaignBuilder.INCUMBENT, Map.of("safety", new TrialInputs(2, List.of(100L, 0L)))));
		Campaign campaign = new CampaignBuilder().confirmed().working(1).optimization(evidence).build();
		JsonNode cells = StatusReport.of(campaign, ReportEvidence.NONE).toJson(JsonMapper.builder().build()).
			get("trial_input_tokens");
		for (JsonNode cell : cells)
		{
			if (cell.get("cell").asString().equals("safety"))
			{
				requireThat(cell.get("working_median").asLong(), "workingMedian").isEqualTo(30);
				requireThat(cell.get("incumbent_median").asLong(), "incumbentMedian").isEqualTo(0);
				requireThat(cell.get("working_outcomes").asLong(), "outcomes").isEqualTo(5);
				requireThat(cell.get("working_sessions").asInt(), "observed").isEqualTo(4);
				requireThat(cell.get("unavailable").isNull(), "available").isTrue();
			}
			else
				requireThat(cell.get("unavailable").asString(), "reason").isNotBlank();
		}
	}

	/**
	 * Returns a builder of a confirmed campaign whose working candidate is candidate 1, with ready baselines.
	 *
	 * @return the builder
	 */
	private static CampaignBuilder confirmed()
	{
		return new CampaignBuilder().confirmed().working(1).readyBaseline();
	}

	/**
	 * Returns the report of a cell.
	 *
	 * @param report the report
	 * @param cell the cell's identifier
	 * @return the cell's report
	 * @throws AssertionError if the report has no such cell
	 */
	private static CellReport cell(StatusReport report, String cell)
	{
		for (CellReport candidate : report.cells())
		{
			if (candidate.cell().equals(cell))
				return candidate;
		}
		throw new AssertionError("No cell " + cell + " in " + report.cells());
	}

	/**
	 * Verifies that development results carry the labels {@code development estimate} and {@code not certified}, and
	 * that an exploratory interval is the only error bar.
	 */
	@Test
	public void developmentResultsAreLabelled()
	{
		// Requirement: R-D8
		// Requirement: R-P1
		// formatting: 90 passes in 100 outcomes -> 0.9; safety: 100 in 100 -> 1.0
		Campaign campaign = confirmed().development("formatting", 90, 10).development("safety", 100, 0).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.phase(), "phase").isEqualTo(Phase.DEVELOPMENT);
		requireThat(report.candidate(), "candidate").isEqualTo(1);

		CellReport formatting = cell(report, "formatting");
		requireThat(formatting.passRate().label(), "label").isEqualTo("development estimate");
		requireThat(formatting.passRate().rate(), "rate").isEqualTo(0.9);
		requireThat(formatting.comparison().state(), "state").isEqualTo("not certified");
		requireThat(formatting.comparison().readiness(), "readiness").isEqualTo("not ready");
		// 90% is below the gate's 99%, and the latest complete batches [10, 0] hold more than 1 failure
		requireThat(formatting.comparison().reasons().size(), "reasons").isEqualTo(2);
		requireThat(formatting.comparison().lowerBound(), "lowerBound").isNull();
		requireThat(formatting.errorBars().size(), "errorBars").isEqualTo(1);
		CellReport.ErrorBar bar = formatting.errorBars().getFirst();
		requireThat(bar.exploratory(), "exploratory").isTrue();
		requireThat(bar.errorProbability(), "errorProbability").isNull();
		requireThat(bar.interval(), "interval").isEqualTo(ExploratoryBounds.wilson(90, 100));

		CellReport safety = cell(report, "safety");
		requireThat(safety.comparison().state(), "safetyState").isEqualTo("not certified");
		requireThat(report.summary(), "summary").contains("development estimate").contains("not certified");
	}

	/**
	 * Verifies the exploratory improvement of a development candidate over the incumbent baseline.
	 */
	@Test
	public void developmentImprovementIsExploratory()
	{
		// Requirement: R-P1
		// candidate: 90 of 100 (0.9); incumbent baseline 1 (index 1): 50 of 50 (1.0) -> -10.0 percentage points
		// difference interval: [wilson(90,100).lower - wilson(50,50).upper, wilson(90,100).upper - wilson(50,50).lower]
		Campaign campaign = confirmed().development("formatting", 90, 10).development("safety", 100, 0).build();
		CellReport.Improvement improvement = cell(StatusReport.of(campaign, ReportEvidence.NONE),
			"formatting").improvement();
		requireThat(improvement.unavailable(), "unavailable").isNull();
		requireThat(Math.abs(improvement.points() + 10.0) < 1e-9, "pointsAreMinusTen").isTrue();
		requireThat(improvement.incumbent(), "incumbent").isEqualTo(new CellReport.IncumbentCounts(1, 50, 50));
		Interval candidate = ExploratoryBounds.wilson(90, 100);
		Interval incumbent = ExploratoryBounds.wilson(50, 50);
		requireThat(improvement.difference().lower(), "lower").
			isEqualTo(candidate.lower() - incumbent.upper());
		requireThat(improvement.difference().upper(), "upper").
			isEqualTo(candidate.upper() - incumbent.lower());
		requireThat(improvement.state(), "state").isNull();
	}

	/**
	 * Verifies that every output that cannot be shown carries its reason.
	 */
	@Test
	public void unavailableValuesAreExplained()
	{
		// Requirement: R-P1
		// safety has no development outcomes; formatting is the incumbent when the working candidate is 0
		Campaign campaign = new CampaignBuilder().confirmed().readyBaseline().development("formatting", 100, 0).
			build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		CellReport safety = cell(report, "safety");
		requireThat(safety.passRate().rate(), "rate").isNull();
		requireThat(safety.passRate().unavailable(), "rateReason").isNotNull();
		requireThat(safety.errorBars(), "errorBars").isEmpty();
		requireThat(safety.improvement().unavailable(), "improvementReason").isNotNull();
		CellReport formatting = cell(report, "formatting");
		requireThat(formatting.improvement().unavailable(), "incumbentReason").isNotNull();
	}

	/**
	 * Verifies the four outputs of a running certification attempt, with the comparison state at {@code β_i / K}.
	 */
	@Test
	public void formalFourOutputs()
	{
		// Requirement: R-P1
		// Requirement: R-F6
		// Attempt 1 boundaries: certifies at 100 + 50F; proven better than the incumbent at 60 + 40F; frozen
		// UI = 0.9000, LI = 0.8000.
		// formatting: S = 120, F = 0 -> certified (120 >= 100) and proven better (120 >= 60)
		// safety:     S = 60,  F = 1 -> undecided (60 < 150) and undecided against the incumbent (60 < 100)
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			category("formatting", 120, 0).category("safety", 60, 1).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.phase(), "phase").isEqualTo(Phase.FORMAL);
		requireThat(report.attempt(), "attempt").isEqualTo(1);
		SignificanceLevel alpha = ErrorBudget.certificationLevel(1);

		CellReport formatting = cell(report, "formatting");
		requireThat(formatting.passRate().label(), "label").isEqualTo("formal estimate");
		requireThat(formatting.passRate().rate(), "rate").isEqualTo(1.0);
		requireThat(formatting.comparison().state(), "state").isEqualTo("certified");
		requireThat(formatting.comparison().reasons(), "reasons").isEmpty();
		double lower = ConfidenceBounds.lower(120, 0, alpha).value();
		double upper = ConfidenceBounds.upper(120, 0, alpha).value();
		requireThat(formatting.comparison().lowerBound(), "lowerBound").isEqualTo(lower);
		requireThat(formatting.comparison().upperBound(), "upperBound").isEqualTo(upper);
		requireThat(formatting.comparison().alpha(), "alpha").isEqualTo(alpha.probability());
		CellReport.Improvement improvement = formatting.improvement();
		requireThat(improvement.state(), "improvementState").isEqualTo("proven better");
		requireThat(improvement.incumbentUpper(), "UI").isEqualTo(0.9);
		requireThat(improvement.incumbentLower(), "LI").isEqualTo(0.8);
		// difference interval [LC - UI, UC - LI]
		requireThat(Math.abs(improvement.difference().lower() - (lower - 0.9)) < EPSILON, "differenceLower").isTrue();
		requireThat(Math.abs(improvement.difference().upper() - (upper - 0.8)) < EPSILON, "differenceUpper").isTrue();
		requireThat(improvement.points(), "points").isEqualTo(0.0);
		requireThat(improvement.incumbent(), "incumbent").isEqualTo(new CellReport.IncumbentCounts(1, 50, 50));
		requireThat(improvement.deficiency(), "deficiency").isEqualTo("deficiency unproven");
		// the one-sided certification bound stays apart from the descriptive two-sided interval
		requireThat(formatting.errorBars().size(), "errorBars").isEqualTo(2);
		requireThat(formatting.errorBars().get(0).oneSided(), "certificationBoundOneSided").isTrue();
		requireThat(formatting.errorBars().get(0).errorProbability(), "certificationBoundError").
			isEqualTo(alpha.probability());
		requireThat(formatting.errorBars().get(1).oneSided(), "descriptiveOneSided").isFalse();
		requireThat(formatting.errorBars().get(1).exploratory(), "descriptiveExploratory").isTrue();

		CellReport safety = cell(report, "safety");
		requireThat(safety.comparison().state(), "safetyState").isEqualTo("undecided");
		requireThat(safety.improvement().state(), "safetyImprovementState").isEqualTo("undecided");
	}

	/**
	 * Verifies the remaining comparison labels of a formal cell.
	 */
	@Test
	public void formalComparisonLabels()
	{
		// Requirement: R-P1
		// formatting: S = 295, F = 5 reaches the cap of 300 without certifying (needs 296) and without being clearly
		// below (needs 6 failures). safety: S = 0, F = 8 is clearly below and proven worse (needs 8 failures).
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			category("formatting", 295, 5).category("safety", 0, 8).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(cell(report, "formatting").comparison().state(), "capReached").isEqualTo("inconclusive");
		requireThat(cell(report, "safety").comparison().state(), "clearlyBelow").isEqualTo("clearly below");
		requireThat(cell(report, "safety").improvement().state(), "worse").isEqualTo("proven worse");
	}

	/**
	 * Verifies that incumbent counts are not shown for a baseline that differs from the one that the attempt froze.
	 */
	@Test
	public void incumbentCountsRequireTheFrozenBaseline()
	{
		// Requirement: R-P1
		// The attempt froze baseline 1; the campaign now holds baseline 2.
		Campaign frozen = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			category("formatting", 120, 0).category("safety", 120, 0).build();
		Campaign campaign = new Campaign(frozen.header(), frozen.ceiling(), frozen.lineages(), frozen.identity(),
			frozen.incumbent(), frozen.candidates(), frozen.authorAttempts(), frozen.development(),
			frozen.failedCells(), new IncumbentBaseline(2, frozen.baseline().counts()), frozen.attempts(),
			frozen.trials(),
			frozen.sequences(), frozen.evidenceRevision(), frozen.slowStart(), frozen.throttle(),
			frozen.authorizedTargets(), frozen.pendingDecisions(), frozen.revisionRequested(), frozen.outcome(),
			frozen.delivery(), frozen.optimization());
		CellReport.Improvement improvement = cell(StatusReport.of(campaign, ReportEvidence.NONE),
			"formatting").improvement();
		requireThat(improvement.incumbent(), "incumbent").isNull();
		requireThat(improvement.points(), "points").isNull();
		requireThat(improvement.state(), "state").isEqualTo("proven better");
	}

	/**
	 * Verifies that allocation counts are shown apart from benchmark weights and that no pooled pass fraction is
	 * presented.
	 */
	@Test
	public void allocationIsSeparateFromBenchmarkWeights()
	{
		// Requirement: R-P1
		// formatting has 2 tests (weight 1/2 each), safety has 1 (weight 1). Adaptive allocation gave formatting 120
		// outcomes and safety 61. The pooled fraction (120 + 60) / 181 = 0.9945 must not appear.
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			category("formatting", 120, 0).category("safety", 60, 1).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.allocation().get("formatting"), "formattingAllocation").isEqualTo(120);
		requireThat(report.allocation().get("safety"), "safetyAllocation").isEqualTo(61);
		List<Benchmark.CellWeights> weights = report.benchmark().cells();
		requireThat(weights, "weights").isEqualTo(List.of(new Benchmark.CellWeights("formatting", 2, 0.5),
			new Benchmark.CellWeights("safety", 1, 1.0)));

		ObjectNode json = report.toJson(JsonMapper.builder().build());
		requireThat(json.has("allocation"), "allocation").isTrue();
		requireThat(json.has("benchmark"), "benchmark").isTrue();
		String text = json.toString() + report.summary();
		requireThat(text, "text").doesNotContain("pooled").doesNotContain("99.4").doesNotContain("0.9944").
			doesNotContain("0.9945");
	}

	/**
	 * Verifies that the outcome names match the design's table exactly.
	 */
	@Test
	public void outcomeLabelsMatchTheDesign()
	{
		// Requirement: R-P3
		// The campaign outcome labels, in declaration order
		List<String> expected = List.of("certified", "improved, not certified", "not certified: below minimum",
			"not certified: inconclusive", "not certified: plateau",
			"not certified: stopped for contract revision", "not certified: testing environment changed",
			"certification unavailable: unsupported instruction source",
			"certification unavailable: ineligible trial effect", "certification unavailable: unsupported output",
			"certification unavailable: unsupported test interaction",
			"certification unavailable: invalid statistical assumptions", "interrupted", "infrastructure failure");
		requireThat(StatusReport.outcomeLabels(), "labels").isEqualTo(expected);
	}

	/**
	 * Verifies that every campaign outcome appears under its design label, and that interruption and infrastructure
	 * failure are reported apart from compliance outcomes.
	 */
	@Test
	public void outcomesAreReportedUnderTheirLabels()
	{
		// Requirement: R-P3
		List<String> reported = new ArrayList<>();
		for (Outcome outcome : Outcome.values())
		{
			if (outcome == Outcome.NOT_CERTIFIED)
				continue;
			Campaign campaign = confirmed().readyDevelopment().outcome(outcome).build();
			reported.add(StatusReport.of(campaign, ReportEvidence.NONE).outcome());
		}
		ReportEvidence interrupted = new ReportEvidence(List.of(), Map.of(), List.of(), List.of(), false, true,
			null);
		Campaign running = confirmed().readyDevelopment().build();
		reported.add(StatusReport.of(running, interrupted).outcome());
		ReportEvidence failed = new ReportEvidence(List.of(), Map.of(), List.of(), List.of(), false, false,
			"The runner exited with status 7");
		StatusReport failure = StatusReport.of(running, failed);
		reported.add(failure.outcome());
		requireThat(reported, "reported").isEqualTo(StatusReport.outcomeLabels());
		requireThat(failure.outcomeDetail(), "detail").isEqualTo("The runner exited with status 7");
		requireThat(StatusReport.of(running, ReportEvidence.NONE).outcome(), "noOutcome").isNull();
	}

	/**
	 * Verifies that delivery of an uncertified candidate is labelled apart from the outcome table.
	 */
	@Test
	public void deliveryIsLabelled()
	{
		// Requirement: R-P3
		Candidate candidate = new Candidate(1, CampaignBuilder.snapshot("candidate-1"));
		Campaign campaign = confirmed().readyDevelopment().lifecycle(Lifecycle.FINISHED).outcome(Outcome.CERTIFIED).
			delivery(new DeliveryRequest(candidate, 0, Outcome.NOT_CERTIFIED)).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.delivery(), "delivery").isEqualTo("not certified");
		requireThat(report.deliveryAttempt(), "deliveryAttempt").isEqualTo(0);
		requireThat(report.status().label(), "status").isEqualTo("certified");
	}

	/**
	 * Verifies that a condition that one cell observed is listed under that cell's name, in the conditions and in the
	 * claim.
	 */
	@Test
	public void conditionsNameEachCellsValue()
	{
		// Requirement: R-E17
		MeasurementIdentity campaignWide = CampaignBuilder.confirmedIdentity("model-a");
		MeasurementIdentity identity = new MeasurementIdentity(campaignWide.values(),
			Map.of("formatting", Map.of(MeasurementCondition.MODEL, "model-c")));
		StatusReport report = StatusReport.of(confirmed().identity(identity).readyDevelopment().build(),
			ReportEvidence.NONE);
		requireThat(report.conditions().get(MeasurementCondition.MODEL.name()), "model").isEqualTo("model-a");
		requireThat(report.conditions().get(MeasurementCondition.MODEL.name() + "[formatting]"), "cellModel").
			isEqualTo("model-c");
		requireThat(report.claim(), "claim").contains("MODEL[formatting]=model-c");
	}

	/**
	 * Verifies that the claim names the frozen judge, the benchmark and the conditions, and that limitations are
	 * listed.
	 */
	@Test
	public void claimNamesJudgeBenchmarkAndConditions()
	{
		// Requirement: R-P4
		Campaign campaign = confirmed().readyDevelopment().build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.claim(), "claim").contains(ContractFixtures.judge().profile().label()).
			contains(ContractFixtures.judge().decisionRule()).contains("balanced benchmark").
			contains("not usage frequencies").contains("model-a");
		requireThat(report.conditions().get(MeasurementCondition.MODEL.name()), "model").isEqualTo("model-a");
		requireThat(report.conditions().size(), "conditions").
			isEqualTo(campaign.identity().values().size());
		requireThat(report.limitations(), "limitations").contains("Only the English prompts are covered.");
		// the contract's own limitations come after the fixed ones, which are always listed
		requireThat(report.limitations().size(), "limitationCount").isGreaterThan(1);
		requireThat(report.summary(), "summary").contains(ContractFixtures.judge().profile().label()).
			contains("Only the English prompts are covered.");
	}

	/**
	 * Verifies that the limitation about symbolic links appears when staging replaced links with copies.
	 */
	@Test
	public void symbolicLinkLimitationIsRecorded()
	{
		// Requirement: R-W6
		Campaign campaign = confirmed().readyDevelopment().build();
		ReportEvidence links = new ReportEvidence(List.of(), Map.of(), List.of(), List.of(), true, false, null);
		List<String> without = StatusReport.of(campaign, ReportEvidence.NONE).limitations();
		List<String> with = StatusReport.of(campaign, links).limitations();
		requireThat(with.size(), "size").isEqualTo(without.size() + 1);
		boolean mentioned = false;
		for (String limitation : with)
		{
			if (limitation.contains("symbolic link"))
				mentioned = true;
		}
		requireThat(mentioned, "mentionsSymbolicLinks").isTrue();
	}

	/**
	 * Verifies the keep-improvement choice and the evidence that qualifies it.
	 */
	@Test
	public void keepImprovementIsOfferedWithEvidence()
	{
		// Requirement: R-F6
		// Attempt 1 ended inconclusive. formatting: S = 120, F = 0 is proven better (120 >= 60). safety: S = 95,
		// F = 5 (100 outcomes) is not proven better (needs 260), not proven worse (needs 8 failures) and not
		// certified (needs 296), so it qualifies through its 100 outcomes.
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			category("formatting", 120, 0).category("safety", 95, 5).outcome(Outcome.INCONCLUSIVE).campaign().
			pending(new PendingDecision.ImprovementProven(1)).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.status().label(), "status").isEqualTo("awaiting-user");
		List<String> ids = new ArrayList<>();
		for (ChoiceItem choice : report.choices())
			ids.add(choice.id());
		requireThat(ids, "ids").isEqualTo(List.of("keep-improvement", "optimize-further", "stop"));
		requireThat(report.choices().getFirst().attempt(), "attempt").isEqualTo(1);

		requireThat(report.keepOffers().size(), "offers").isEqualTo(1);
		KeepOffer offer = report.keepOffers().getFirst();
		requireThat(offer.attempt(), "offerAttempt").isEqualTo(1);
		requireThat(offer.historical(), "historical").isFalse();
		requireThat(offer.differing(), "differing").isEmpty();
		requireThat(offer.weakestLowerBound(), "weakest").isEqualTo(ExploratoryBounds.wilson(95, 100).lower());
		KeepOffer.CellEvidence formatting = offer.cells().get(0);
		requireThat(formatting.cell(), "firstCell").isEqualTo("formatting");
		requireThat(formatting.state(), "formattingState").isEqualTo("proven better");
		requireThat(formatting.incumbentUpper(), "UI").isEqualTo(0.9);
		requireThat(formatting.incumbentLower(), "LI").isEqualTo(0.8);
		requireThat(formatting.note(), "formattingNote").isNull();
		KeepOffer.CellEvidence safety = offer.cells().get(1);
		requireThat(safety.state(), "safetyState").isEqualTo("undecided");
		requireThat(safety.passes(), "safetyPasses").isEqualTo(95);
		requireThat(safety.trials(), "safetyTrials").isEqualTo(100);
		// the difference interval's lower endpoint is below 0, so the cell might be slightly worse
		requireThat(safety.difference().lower(), "differenceLower").isLessThan(0.0);
		requireThat(safety.note(), "safetyNote").isNotNull();

		JsonNode offerNode = report.toJson(JsonMapper.builder().build()).path("keepOffers").get(0);
		requireThat(offerNode.has("categories"), "offerCategories").isFalse();
		requireThat(offerNode.path("cells").get(0).path("cell").asString(), "offerCell").isEqualTo("formatting");
	}

	/**
	 * Verifies that an attempt measured under an earlier identity is offered as historical with its differing
	 * conditions.
	 */
	@Test
	public void historicalOfferNamesDifferingConditions()
	{
		// Requirement: R-F6
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			identity(CampaignBuilder.confirmedIdentity("model-b")).category("formatting", 120, 0).
			category("safety", 95, 5).outcome(Outcome.INCONCLUSIVE).campaign().
			pending(new PendingDecision.ImprovementProven(1)).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		KeepOffer offer = report.keepOffers().getFirst();
		requireThat(offer.historical(), "historical").isTrue();
		requireThat(offer.differing(), "differing").isEqualTo(List.of(MeasurementCondition.MODEL.name()));
		requireThat(report.choices().getFirst().historical(), "choiceHistorical").isTrue();
		AttemptSummary summary = report.attempts().getFirst();
		requireThat(summary.historical(), "attemptHistorical").isTrue();
		requireThat(summary.changed(), "changed").contains(MeasurementCondition.MODEL.name());
	}

	/**
	 * Verifies the status values and the checkpoint choice.
	 */
	@Test
	public void statusFollowsLifecycleAndPending()
	{
		// Requirement: R-P1
		Campaign base = confirmed().readyDevelopment().build();
		requireThat(StatusReport.of(base, ReportEvidence.NONE).status().label(), "running").isEqualTo("running");
		Campaign waiting = confirmed().readyDevelopment().pending(new PendingDecision.CheckpointReached("safety", 200)).
			build();
		StatusReport report = StatusReport.of(waiting, ReportEvidence.NONE);
		requireThat(report.status().label(), "awaiting").isEqualTo("awaiting-user");
		requireThat(report.pending(), "pending").isNotNull().contains("safety");
		ChoiceItem more = report.choices().getFirst();
		requireThat(more.choice(), "choice").isEqualTo(Choice.MORE_EVIDENCE);
		requireThat(more.reviewPointAbove(), "reviewPointAbove").isEqualTo(200);
		requireThat(StatusReport.of(confirmed().readyDevelopment().lifecycle(Lifecycle.CLOSED).
			outcome(Outcome.PLATEAU).build(),
			ReportEvidence.NONE).status().label(), "closed").isEqualTo("not certified: plateau");
		requireThat(StatusReport.of(confirmed().readyDevelopment().lifecycle(Lifecycle.CANCELLED).build(),
			ReportEvidence.NONE).status().label(), "cancelled").isEqualTo("awaiting-user");
		requireThat(StatusReport.of(new CampaignBuilder().build(), ReportEvidence.NONE).status().label(),
			"unconfirmedStatus").
			isEqualTo("awaiting-user");
		requireThat(StatusReport.of(new CampaignBuilder().build(), ReportEvidence.NONE).phase(), "unconfirmed").
			isEqualTo(Phase.UNCONFIRMED);
	}

	/**
	 * Verifies the status domain: one accepted value, and the excluded absence of a value.
	 */
	@Test
	public void statusDomainIsConstrained()
	{
		// Requirement: R-P1
		requireThat(new ReportStatus.Ended(Outcome.PLATEAU).label(), "accepted").isEqualTo("not certified: plateau");
		requireThat(new ReportStatus.Active(Status.BUSY).label(), "busy").isEqualTo("busy");
		// Excluded: no arbitrary text, such as "finished", can be constructed; the only invalid value is null.
		try
		{
			new ReportStatus.Ended(null);
			throw new AssertionError("null accepted");
		}
		catch (NullPointerException _)
		{
			// expected
		}
	}

	/**
	 * Verifies the JSON status carries the labelled outputs and no forecast.
	 */
	@Test
	public void jsonStatusHasNoForecast()
	{
		// Requirement: R-E12
		// Requirement: R-P1
		Campaign campaign = confirmed().development("formatting", 60, 0).development("safety", 100, 0).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		ObjectNode json = report.toJson(JsonMapper.builder().build());
		requireThat(json.path("status").asString(), "status").isEqualTo("running");
		requireThat(json.path("phase").asString(), "phase").isEqualTo("development");
		requireThat(json.path("remaining").path("total").asInt(), "remaining").isEqualTo(226);
		JsonNode first = json.path("cells").get(0);
		requireThat(first.path("cell").asString(), "cell").isEqualTo("formatting");
		requireThat(first.path("passRate").path("label").asString(), "label").isEqualTo("development estimate");
		requireThat(first.path("comparison").path("state").asString(), "state").isEqualTo("not certified");
		String text = json.toString().toLowerCase(java.util.Locale.ROOT);
		requireThat(text, "text").doesNotContain("tokentotal").doesNotContain("timeestimate").
			doesNotContain("completionprobability").doesNotContain("remainingtime");
	}

	/**
	 * Verifies that an unavailable certification is reported by its outcome label alone, without repeating the
	 * wording that the label already carries.
	 */
	@Test
	public void unavailableCertificationShowsLabel()
	{
		// Requirement: R-P1
		Campaign campaign = confirmed().
			pending(new PendingDecision.CertificationUnavailable(Outcome.UNSUPPORTED_OUTPUT)).build();

		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		requireThat(report.pending(), "pending").isEqualTo("certification unavailable: unsupported output");
	}

	/**
	 * Verifies that a pending authority warning shows its requirement, link path, resolved target, limit and choices,
	 * in the JSON and in a summary line.
	 */
	@Test
	public void authorityWarningIsShown()
	{
		// Requirement: R-W4
		AuthorityWarning warning = new AuthorityWarning("R1", Path.of("rules/shared.md"),
			Path.of("/external/shared.md"), Limit.EXTERNAL_TARGET_READ_ONLY, List.of(
				AuthorityWarning.Choice.AUTHORIZE_TARGET, AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE,
				AuthorityWarning.Choice.STOP_CAMPAIGN, AuthorityWarning.Choice.REVISE_CAMPAIGN));
		Campaign campaign = confirmed().pending(new PendingDecision.AuthorityWarnings(List.of(warning))).build();

		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		requireThat(report.status().label(), "status").isEqualTo("awaiting-user");
		requireThat(report.pending(), "pending").isNotNull().contains("R1");
		requireThat(report.authorityWarnings(), "authorityWarnings").isEqualTo(List.of(warning));
		requireThat(report.choices().stream().map(ChoiceItem::id).toList(), "choices").
			isEqualTo(List.of("authorize-target", "use-authorized-source", "stop"));
		ObjectNode json = report.toJson(JsonMapper.builder().build());
		JsonNode shown = json.path("authorityWarnings").get(0);
		requireThat(shown.path("requirement").asString(), "requirement").isEqualTo("R1");
		requireThat(shown.path("path").asString(), "path").isEqualTo("rules/shared.md");
		requireThat(shown.path("target").asString(), "target").isEqualTo("/external/shared.md");
		requireThat(shown.path("limit").asString(), "limit").isEqualTo("external-target-read-only");
		List<String> choices = new ArrayList<>();
		for (JsonNode choice : shown.path("choices"))
			choices.add(choice.asString());
		requireThat(choices, "warningChoices").
			isEqualTo(List.of("authorize-target", "use-authorized-source", "stop", "revise"));
		requireThat(report.summary(), "summary").contains("Authority warning: requirement R1 needs an edit at " +
			"rules/shared.md, which resolves to /external/shared.md (limit: external target read only); " +
			"choices: authorize target (--target /external/shared.md), use authorized source, stop, revise");
	}

	/**
	 * Verifies that a category under two profiles is reported as two cells with separate counts, readiness and
	 * decisions, that the claim counts cells, and that the status shows the trial profiles.
	 */
	@Test
	public void twoCellCategoryIsReportedPerCell()
	{
		// Requirement: R-C11
		// Requirement: R-C10
		Contract contract = ContractFixtures.twoCellContract();
		Campaign development = new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().
			development(ContractFixtures.BIG_CELL, 100, 0).development(ContractFixtures.SMALL_CELL, 60, 1).build();
		StatusReport report = StatusReport.of(development, ReportEvidence.NONE);
		requireThat(report.cells().stream().map(CellReport::cell).toList(), "cells").
			isEqualTo(List.of(ContractFixtures.BIG_CELL, ContractFixtures.SMALL_CELL, "safety"));
		requireThat(cell(report, ContractFixtures.BIG_CELL).comparison().readiness(), "bigReadiness").
			isEqualTo("ready");
		requireThat(cell(report, ContractFixtures.SMALL_CELL).trials(), "smallTrials").isEqualTo(61);
		requireThat(cell(report, ContractFixtures.SMALL_CELL).comparison().readiness(), "smallReadiness").
			isEqualTo("not ready");
		requireThat(report.claim(), "claim").contains("3 cells");
		requireThat(report.summary(), "summary").contains("Cell " + ContractFixtures.SMALL_CELL + ":").
			contains(ContractFixtures.TWO_PROFILES.source());

		JsonNode profiles = report.toJson(JsonMapper.builder().build()).path("trialProfiles");
		requireThat(profiles.path("inventorySource").asString(), "inventorySource").
			isEqualTo(ContractFixtures.TWO_PROFILES.source());
		requireThat(profiles.path("inventory").size(), "inventory").isEqualTo(2);
		requireThat(profiles.path("bindings").get(0).path("file").asString(), "binding").
			isEqualTo(".claude/skills/safety/SKILL.md");
		JsonNode small = profiles.path("cells").get(1);
		requireThat(small.path("id").asString(), "small.id").isEqualTo(ContractFixtures.SMALL_CELL);
		requireThat(small.path("category").asString(), "small.category").isEqualTo("formatting");
		requireThat(small.path("model").asString(), "small.model").isEqualTo("small");
		requireThat(small.path("effort").asString(), "small.effort").isEqualTo("high");

		// Each cell is decided on its own counts: big certifies, small is clearly below, safety is undecided
		Campaign formal = new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.boundaries(3)).
			category(ContractFixtures.BIG_CELL, 120, 0).category(ContractFixtures.SMALL_CELL, 0, 8).
			category("safety", 60, 1).build();
		StatusReport formalReport = StatusReport.of(formal, ReportEvidence.NONE);
		requireThat(cell(formalReport, ContractFixtures.BIG_CELL).comparison().state(), "big").
			isEqualTo("certified");
		requireThat(cell(formalReport, ContractFixtures.SMALL_CELL).comparison().state(), "small").
			isEqualTo("clearly below");
		requireThat(cell(formalReport, "safety").comparison().state(), "safety").isEqualTo("undecided");
	}

	/**
	 * Verifies that every JSON status key whose value is a cell identifier names it a cell, and that {@code category}
	 * remains only where the value is a category.
	 */
	@Test
	public void jsonKeysNameCells()
	{
		// Requirement: R-C11
		// Requirement: R-E12
		Contract contract = ContractFixtures.twoCellContract();
		Campaign campaign = new CampaignBuilder().confirmed(contract, 1).working(1).readyBaseline().
			development(ContractFixtures.BIG_CELL, 100, 0).development(ContractFixtures.SMALL_CELL, 60, 1).build();
		ObjectNode json = StatusReport.of(campaign, ReportEvidence.NONE).toJson(JsonMapper.builder().build());
		List<String> cells = List.of(ContractFixtures.BIG_CELL, ContractFixtures.SMALL_CELL, "safety");

		requireThat(json.has("categories"), "categories").isFalse();
		requireThat(values(json.path("cells"), "cell"), "cells").isEqualTo(cells);
		requireThat(json.path("allocation").propertyNames(), "allocation").containsExactly(cells);
		requireThat(json.path("benchmark").has("categories"), "benchmark.categories").isFalse();
		requireThat(values(json.path("benchmark").path("cells"), "cell"), "benchmark.cells").isEqualTo(cells);
		JsonNode entries = json.path("remaining").path("entries");
		requireThat(entries.isEmpty(), "entries.isEmpty").isFalse();
		requireThat(cells, "cells").containsAll(values(entries, "cell"));
		JsonNode profileCells = json.path("trialProfiles").path("cells");
		requireThat(values(profileCells, "id"), "trialProfiles.cells").isEqualTo(cells);
		requireThat(values(profileCells, "category"), "trialProfiles.categories").
			isEqualTo(List.of("formatting", "formatting", "safety"));
	}

	/**
	 * Verifies that the text and JSON status show each category's active development cells, including a cell that
	 * became active by failing, and whether development has widened.
	 */
	@Test
	public void cellTierIsShown()
	{
		// Requirement: R-E19
		CampaignBuilder builder = new CampaignBuilder().confirmed(TierFixtures.tieredContract(), 1).working(1).
			readyBaseline().failedCell(TierFixtures.BIG_MEDIUM);
		List<String> formatting = List.of(TierFixtures.BIG_LOW, TierFixtures.BIG_MEDIUM, TierFixtures.BIG_XHIGH,
			TierFixtures.SMALL_HIGH);
		StatusReport report = StatusReport.of(builder.build(), ReportEvidence.NONE);
		requireThat(report.summary(), "summary").
			contains("Active development cells of category formatting: " + String.join(", ", formatting)).
			contains("Active development cells of category safety: " + TierFixtures.SAFETY).
			contains("Development widened to every cell: no");
		JsonNode tier = report.toJson(JsonMapper.builder().build()).path("cellTier");
		requireThat(tier.path("widened").asBoolean(), "widened").isFalse();
		JsonNode active = tier.path("activeCells");
		requireThat(values(active, "category"), "categories").isEqualTo(List.of("formatting", "safety"));
		List<String> cells = new ArrayList<>();
		for (JsonNode cell : active.get(0).path("cells"))
			cells.add(cell.asString());
		requireThat(cells, "formattingCells").isEqualTo(formatting);

		StatusReport widened = StatusReport.of(builder.widened().build(), ReportEvidence.NONE);
		requireThat(widened.summary(), "widenedSummary").contains("Development widened to every cell: yes");
		requireThat(widened.toJson(JsonMapper.builder().build()).path("cellTier").path("widened").asBoolean(),
			"widenedJson").isTrue();

		StatusReport unconfirmed = StatusReport.of(new CampaignBuilder().build(), ReportEvidence.NONE);
		requireThat(unconfirmed.toJson(JsonMapper.builder().build()).path("cellTier").isNull(), "unconfirmed").
			isTrue();
	}

	/**
	 * Returns one text field of each element of an array.
	 *
	 * @param array the array
	 * @param field the field's name
	 * @return the values, in order
	 * @throws AssertionError if an element lacks the field
	 */
	private static List<String> values(JsonNode array, String field)
	{
		List<String> values = new ArrayList<>();
		for (JsonNode element : array)
		{
			if (!element.has(field))
				throw new AssertionError("No " + field + " in " + element);
			values.add(element.path(field).asString());
		}
		return values;
	}
}
