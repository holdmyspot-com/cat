package io.github.cowwoc.cat.common.optimizer.report;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignBuilder;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.contract.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.statistics.ConfidenceBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.Interval;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
	 * Returns a builder of a confirmed campaign whose working candidate is candidate 1, with ready baselines.
	 *
	 * @return the builder
	 */
	private static CampaignBuilder confirmed()
	{
		return new CampaignBuilder().confirmed().working(1).readyBaseline();
	}

	/**
	 * Returns the report of a category.
	 *
	 * @param report the report
	 * @param category the category
	 * @return the category's report
	 * @throws AssertionError if the report has no such category
	 */
	private static CategoryReport category(StatusReport report, String category)
	{
		for (CategoryReport candidate : report.categories())
		{
			if (candidate.category().equals(category))
				return candidate;
		}
		throw new AssertionError("No category " + category + " in " + report.categories());
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

		CategoryReport formatting = category(report, "formatting");
		requireThat(formatting.passRate().label(), "label").isEqualTo("development estimate");
		requireThat(formatting.passRate().rate(), "rate").isEqualTo(0.9);
		requireThat(formatting.comparison().state(), "state").isEqualTo("not certified");
		requireThat(formatting.comparison().readiness(), "readiness").isEqualTo("not ready");
		// 90% is below the gate's 99%, and the latest complete batches [10, 0] hold more than 1 failure
		requireThat(formatting.comparison().reasons().size(), "reasons").isEqualTo(2);
		requireThat(formatting.comparison().lowerBound(), "lowerBound").isNull();
		requireThat(formatting.errorBars().size(), "errorBars").isEqualTo(1);
		CategoryReport.ErrorBar bar = formatting.errorBars().getFirst();
		requireThat(bar.exploratory(), "exploratory").isTrue();
		requireThat(bar.errorProbability(), "errorProbability").isNull();
		requireThat(bar.interval(), "interval").isEqualTo(ExploratoryBounds.wilson(90, 100));

		CategoryReport safety = category(report, "safety");
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
		CategoryReport.Improvement improvement = category(StatusReport.of(campaign, ReportEvidence.NONE),
			"formatting").improvement();
		requireThat(improvement.unavailable(), "unavailable").isNull();
		requireThat(Math.abs(improvement.points() + 10.0) < 1e-9, "pointsAreMinusTen").isTrue();
		requireThat(improvement.incumbent(), "incumbent").isEqualTo(new CategoryReport.IncumbentCounts(1, 50, 50));
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
		CategoryReport safety = category(report, "safety");
		requireThat(safety.passRate().rate(), "rate").isNull();
		requireThat(safety.passRate().unavailable(), "rateReason").isNotNull();
		requireThat(safety.errorBars(), "errorBars").isEmpty();
		requireThat(safety.improvement().unavailable(), "improvementReason").isNotNull();
		CategoryReport formatting = category(report, "formatting");
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

		CategoryReport formatting = category(report, "formatting");
		requireThat(formatting.passRate().label(), "label").isEqualTo("formal estimate");
		requireThat(formatting.passRate().rate(), "rate").isEqualTo(1.0);
		requireThat(formatting.comparison().state(), "state").isEqualTo("certified");
		requireThat(formatting.comparison().reasons(), "reasons").isEmpty();
		double lower = ConfidenceBounds.lower(120, 0, alpha).value();
		double upper = ConfidenceBounds.upper(120, 0, alpha).value();
		requireThat(formatting.comparison().lowerBound(), "lowerBound").isEqualTo(lower);
		requireThat(formatting.comparison().upperBound(), "upperBound").isEqualTo(upper);
		requireThat(formatting.comparison().alpha(), "alpha").isEqualTo(alpha.probability());
		CategoryReport.Improvement improvement = formatting.improvement();
		requireThat(improvement.state(), "improvementState").isEqualTo("proven better");
		requireThat(improvement.incumbentUpper(), "UI").isEqualTo(0.9);
		requireThat(improvement.incumbentLower(), "LI").isEqualTo(0.8);
		// difference interval [LC - UI, UC - LI]
		requireThat(Math.abs(improvement.difference().lower() - (lower - 0.9)) < EPSILON, "differenceLower").isTrue();
		requireThat(Math.abs(improvement.difference().upper() - (upper - 0.8)) < EPSILON, "differenceUpper").isTrue();
		requireThat(improvement.points(), "points").isEqualTo(0.0);
		requireThat(improvement.incumbent(), "incumbent").isEqualTo(new CategoryReport.IncumbentCounts(1, 50, 50));
		requireThat(improvement.deficiency(), "deficiency").isEqualTo("deficiency unproven");
		// the one-sided certification bound stays apart from the descriptive two-sided interval
		requireThat(formatting.errorBars().size(), "errorBars").isEqualTo(2);
		requireThat(formatting.errorBars().get(0).oneSided(), "certificationBoundOneSided").isTrue();
		requireThat(formatting.errorBars().get(0).errorProbability(), "certificationBoundError").
			isEqualTo(alpha.probability());
		requireThat(formatting.errorBars().get(1).oneSided(), "descriptiveOneSided").isFalse();
		requireThat(formatting.errorBars().get(1).exploratory(), "descriptiveExploratory").isTrue();

		CategoryReport safety = category(report, "safety");
		requireThat(safety.comparison().state(), "safetyState").isEqualTo("undecided");
		requireThat(safety.improvement().state(), "safetyImprovementState").isEqualTo("undecided");
	}

	/**
	 * Verifies the remaining comparison labels of a formal category.
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
		requireThat(category(report, "formatting").comparison().state(), "capReached").isEqualTo("inconclusive");
		requireThat(category(report, "safety").comparison().state(), "clearlyBelow").isEqualTo("clearly below");
		requireThat(category(report, "safety").improvement().state(), "worse").isEqualTo("proven worse");
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
			new IncumbentBaseline(2, frozen.baseline().counts()), frozen.attempts(), frozen.trials(),
			frozen.sequences(), frozen.evidenceRevision(), frozen.slowStart(), frozen.throttle(),
			frozen.authorizedTargets(), frozen.pendingDecisions(), frozen.revisionRequested(), frozen.outcome(),
			frozen.delivery());
		CategoryReport.Improvement improvement = category(StatusReport.of(campaign, ReportEvidence.NONE),
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
		List<Benchmark.CategoryWeights> weights = report.benchmark().categories();
		requireThat(weights, "weights").isEqualTo(List.of(new Benchmark.CategoryWeights("formatting", 2, 0.5),
			new Benchmark.CategoryWeights("safety", 1, 1.0)));

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
		// design.md section 1, in table order
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
		ReportEvidence interrupted = new ReportEvidence(List.of(), java.util.Map.of(), List.of(), List.of(), false, true,
			null);
		Campaign running = confirmed().readyDevelopment().build();
		reported.add(StatusReport.of(running, interrupted).outcome());
		ReportEvidence failed = new ReportEvidence(List.of(), java.util.Map.of(), List.of(), List.of(), false, false,
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
	 * Verifies that the claim names the frozen judge, the benchmark and the conditions, and that limitations are
	 * listed.
	 */
	@Test
	public void claimNamesJudgeBenchmarkAndConditions()
	{
		// Requirement: R-P4
		Campaign campaign = confirmed().readyDevelopment().build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.claim(), "claim").contains(ContractFixtures.judge().model()).
			contains(ContractFixtures.judge().decisionRule()).contains("balanced benchmark").
			contains("not usage frequencies").contains("model-a");
		requireThat(report.conditions().get(MeasurementCondition.MODEL.name()), "model").isEqualTo("model-a");
		requireThat(report.conditions().size(), "conditions").
			isEqualTo(campaign.identity().values().size());
		requireThat(report.limitations(), "limitations").contains("Only the English prompts are covered.");
		// the contract's own limitations come after the fixed ones, which are always listed
		requireThat(report.limitations().size(), "limitationCount").isGreaterThan(1);
		requireThat(report.summary(), "summary").contains(ContractFixtures.judge().model()).
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
		ReportEvidence links = new ReportEvidence(List.of(), java.util.Map.of(), List.of(), List.of(), true, false, null);
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
		KeepOffer.Category formatting = offer.categories().get(0);
		requireThat(formatting.category(), "firstCategory").isEqualTo("formatting");
		requireThat(formatting.state(), "formattingState").isEqualTo("proven better");
		requireThat(formatting.incumbentUpper(), "UI").isEqualTo(0.9);
		requireThat(formatting.incumbentLower(), "LI").isEqualTo(0.8);
		requireThat(formatting.note(), "formattingNote").isNull();
		KeepOffer.Category safety = offer.categories().get(1);
		requireThat(safety.state(), "safetyState").isEqualTo("undecided");
		requireThat(safety.passes(), "safetyPasses").isEqualTo(95);
		requireThat(safety.trials(), "safetyTrials").isEqualTo(100);
		// the difference interval's lower endpoint is below 0, so the category might be slightly worse
		requireThat(safety.difference().lower(), "differenceLower").isLessThan(0.0);
		requireThat(safety.note(), "safetyNote").isNotNull();
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
		JsonNode first = json.path("categories").get(0);
		requireThat(first.path("category").asString(), "category").isEqualTo("formatting");
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
}
