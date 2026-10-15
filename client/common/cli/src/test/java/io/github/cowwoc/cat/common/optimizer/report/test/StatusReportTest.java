package io.github.cowwoc.cat.common.optimizer.report.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.TemporaryDirectories;
import io.github.cowwoc.cat.common.optimizer.campaign.Admission;
import io.github.cowwoc.cat.common.optimizer.campaign.Allocation;
import io.github.cowwoc.cat.common.optimizer.campaign.AllocationRule;
import io.github.cowwoc.cat.common.optimizer.campaign.AttemptResult;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorDiagnosis;
import io.github.cowwoc.cat.common.optimizer.campaign.PromptAuthorKind;
import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.Candidate;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.Choice;
import io.github.cowwoc.cat.common.optimizer.campaign.Pairing;
import io.github.cowwoc.cat.common.optimizer.campaign.DeliveryRequest;
import io.github.cowwoc.cat.common.optimizer.campaign.FailureKind;
import io.github.cowwoc.cat.common.optimizer.campaign.HunkEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.IncumbentBaseline;
import io.github.cowwoc.cat.common.optimizer.campaign.Lifecycle;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementCondition;
import io.github.cowwoc.cat.common.optimizer.campaign.MeasurementIdentity;
import io.github.cowwoc.cat.common.optimizer.campaign.OptimizationEvidence;
import io.github.cowwoc.cat.common.optimizer.campaign.Outcome;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.PendingDecision;
import io.github.cowwoc.cat.common.optimizer.campaign.SoundTest;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevision;
import io.github.cowwoc.cat.common.optimizer.campaign.TestRevisions;
import io.github.cowwoc.cat.common.optimizer.campaign.PromotionRun;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.Round;
import io.github.cowwoc.cat.common.optimizer.campaign.Throttle;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialInputs;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.campaign.test.Builder;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.AddedTestCases;
import io.github.cowwoc.cat.common.optimizer.contract.CertificationScope;
import io.github.cowwoc.cat.common.optimizer.contract.Contract;
import io.github.cowwoc.cat.common.optimizer.contract.DrawStream;
import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import io.github.cowwoc.cat.common.optimizer.contract.test.ContractFixtures;
import io.github.cowwoc.cat.common.optimizer.contract.test.TierFixtures;
import io.github.cowwoc.cat.common.optimizer.execution.PromptAuthorConflict;
import io.github.cowwoc.cat.common.optimizer.execution.Driver;
import io.github.cowwoc.cat.common.optimizer.execution.test.DriverHarness;
import io.github.cowwoc.cat.common.optimizer.inventory.AuthorityWarning;
import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;
import io.github.cowwoc.cat.common.optimizer.inventory.FileInventory;
import io.github.cowwoc.cat.common.optimizer.inventory.Limit;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.optimizer.port.SessionResult;
import io.github.cowwoc.cat.common.optimizer.port.TokenUsage;
import io.github.cowwoc.cat.common.optimizer.report.AttemptSummary;
import io.github.cowwoc.cat.common.optimizer.report.Benchmark;
import io.github.cowwoc.cat.common.optimizer.report.CellReport;
import io.github.cowwoc.cat.common.optimizer.report.ChoiceItem;
import io.github.cowwoc.cat.common.optimizer.report.KeepOffer;
import io.github.cowwoc.cat.common.optimizer.report.OptimizationReport;
import io.github.cowwoc.cat.common.optimizer.report.Phase;
import io.github.cowwoc.cat.common.optimizer.report.ReportEvidence;
import io.github.cowwoc.cat.common.optimizer.report.ReportStatus;
import io.github.cowwoc.cat.common.optimizer.report.Status;
import io.github.cowwoc.cat.common.optimizer.report.StatusReport;
import io.github.cowwoc.cat.common.optimizer.statistics.ConfidenceBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.DecisionBoundaries;
import io.github.cowwoc.cat.common.optimizer.statistics.ErrorBudget;
import io.github.cowwoc.cat.common.optimizer.statistics.ExploratoryBounds;
import io.github.cowwoc.cat.common.optimizer.statistics.Interval;
import io.github.cowwoc.cat.common.optimizer.statistics.SignificanceLevel;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tests {@link StatusReport}. Every campaign state comes from {@link Builder}; hand-computed values are shown
 * in comments.
 */
public final class StatusReportTest
{
	/**
	 * The text and JSON concurrency reports show the configured campaign ceiling.
	 */
	// Requirement: R-E4
	@Test
	public void reportsConfiguredCampaignCeiling()
	{
		StatusReport report = StatusReport.of(new Builder().ceiling(16).build(), ReportEvidence.NONE);
		JsonNode concurrency = report.toJson(JsonMapper.builder().build()).path("concurrency");

		requireThat(concurrency.path("ceiling").asInt(), "ceiling").isEqualTo(16);
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("Concurrency: window 1 of ceiling 16");
	}

	/**
	 * The JSON and the human summary report each retained prompt author manifest's kinds with their classifications,
	 * failures and the hunks that address them, together with the manifest's rejections.
	 */
	@Test
	public void reportsPromptAuthorKinds()
	{
		// Requirement: R-D10
		PromptAuthorDiagnosis diagnosis = new PromptAuthorDiagnosis(3, 1, "safety", Builder.snapshot("candidate-1"),
			List.of(new PromptAuthorKind("skipped confirmation", List.of("f1", "f2"), FailureKind.GUIDANCE,
			List.of("h1"), ""),
				new PromptAuthorKind("judge outage", List.of("f3"), FailureKind.NOT_GUIDANCE, List.of(), "")),
			List.of(new HunkEvidence("h1", Path.of("rules/safety.md"), 1, 2, 1, 3, List.of("f1", "f2"),
				"Overwriting a file", "plain_list")),
			List.of("Uncited changed hunk: CLAUDE.md#2"), Path.of("/runs/prompt-author-3/transcript.jsonl").toAbsolutePath(),
			Path.of("/runs/prompt-author-3/effects").toAbsolutePath());
		Campaign campaign = Transitions.apply(new Builder().confirmed().working(1).build(),
			new CampaignEvent.RetainPromptAuthorDiagnosis(diagnosis));

		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		JsonNode promptAuthor = report.toJson(JsonMapper.builder().build()).path("prompt_author_diagnoses").get(0);

		requireThat(promptAuthor.path("attempt").asInt(), "attempt").isEqualTo(3);
		JsonNode kind = promptAuthor.path("kinds").get(0);
		requireThat(kind.path("name").asString(), "name").isEqualTo("skipped confirmation");
		requireThat(kind.path("classification").asString(), "classification").isEqualTo("guidance");
		requireThat(kind.path("failures").size(), "failures").isEqualTo(2);
		requireThat(kind.path("hunks").get(0).asString(), "hunk").isEqualTo("h1");
		requireThat(promptAuthor.path("kinds").get(1).path("classification").asString(), "notGuidance").
			isEqualTo("not_guidance");
		requireThat(promptAuthor.path("rejections").get(0).asString(), "rejection").
			isEqualTo("Uncited changed hunk: CLAUDE.md#2");
		requireThat(report.summary(ZoneOffset.UTC), "summary").
			contains("Prompt author diagnosis of attempt 3 in cell " +
			"safety: skipped confirmation (guidance), judge outage (not guidance); evidence " +
			"/runs/prompt-author-3/transcript.jsonl\n  Rejected: Uncited changed hunk: CLAUDE.md#2");
	}

	/**
	 * The human summary shows the throttle hold in the user's time zone to the second, while the JSON keeps the UTC
	 * instant.
	 */
	@Test
	public void summaryShowsHoldInUserTimeZone()
	{
		Instant holdUntil = Instant.parse("2026-10-09T21:13:08.789Z");
		StatusReport report = StatusReport.of(new Builder().throttle(new Throttle(holdUntil, 2, 3)).build(),
			ReportEvidence.NONE);
		JsonNode concurrency = report.toJson(JsonMapper.builder().build()).path("concurrency");

		requireThat(concurrency.path("hold_until").asString(), "holdUntil").isEqualTo("2026-10-09T21:13:08.789Z");
		requireThat(report.summary(ZoneId.of("America/Toronto")), "summary").
			contains("throttle hold until 2026-10-09T17:13:08-04:00 (window cap 2, backoffs 3)");
	}

	/**
	 * The human summary shows a throttle hold that falls on a whole minute with its seconds.
	 */
	@Test
	public void summaryShowsZeroSecondsOfHold()
	{
		Instant holdUntil = Instant.parse("2026-01-15T05:00:00Z");
		StatusReport report = StatusReport.of(new Builder().throttle(new Throttle(holdUntil, 1, 1)).build(),
			ReportEvidence.NONE);

		requireThat(report.summary(ZoneId.of("America/Toronto")), "summary").
			contains("throttle hold until 2026-01-15T00:00:00-05:00 ");
	}

	/**
	 * The text and JSON concurrency reports show the default campaign ceiling.
	 */
	// Requirement: R-E4
	@Test
	public void reportsDefaultCampaignCeiling()
	{
		StatusReport report = StatusReport.of(new Builder().build(), ReportEvidence.NONE);
		JsonNode concurrency = report.toJson(JsonMapper.builder().build()).path("concurrency");

		requireThat(concurrency.path("ceiling").asInt(), "ceiling").isEqualTo(8);
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("Concurrency: window 1 of ceiling 8");
	}

	/**
	 * The JSON and human status identify a withdrawn candidate, the addressed cell and both estimates that
	 * triggered its replacement with the predecessor.
	 */
	// Requirement: R-D15
	@Test
	public void reportsRevertedCandidate()
	{
		DrawnTestCase trigger = Builder.targeted("safety", "logs");
		Sha256 snapshot = Builder.snapshot("withdrawn");
		Campaign campaign = new Builder().confirmed().working(1).readyBaseline().
			development("safety", 90, 10).round(new Round(Map.of("safety", 1))).failure(trigger).build();
		campaign = Transitions.apply(campaign, new CampaignEvent.StartPromptAuthorAttempt("safety"));
		campaign = Transitions.apply(campaign, new CampaignEvent.SubmitPromptAuthorAttempt(1, "safety", snapshot,
			List.of()));
		for (int run = 0; run < 3; ++run)
		{
			Allocation allocation = new Allocation(AllocationRule.TARGETED, campaign.evidenceRevision(),
				Instant.EPOCH);
			campaign = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(Purpose.PROMOTION,
				1, snapshot, "safety", trigger, allocation)));
			TrialKey key = campaign.trials().getLast().key();
			campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
			campaign = Transitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.PASS, "", List.of(), null,
				campaign.identity(), null, List.of(), Builder.FILES));
		}
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(snapshot));
		Allocation allocation = new Allocation(AllocationRule.LOWEST_EXPLORATORY_BOUND,
			campaign.evidenceRevision(), Instant.EPOCH);
		campaign = Transitions.apply(campaign, new CampaignEvent.Assign(new Admission.Assign(Purpose.DEVELOPMENT,
			campaign.development().series(), snapshot, "safety", null, allocation)));
		TrialKey key = campaign.trials().getLast().key();
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(key));
		campaign = Transitions.apply(campaign, new CampaignEvent.Complete(key, Verdict.FAIL, "poor outcome", List.of(),
			null, campaign.identity(), null, List.of(), Builder.FILES));

		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		JsonNode reverted = report.toJson(JsonMapper.builder().build()).path("reverts").path(0);
		requireThat(reverted.path("withdrawn").path("number").asInt(), "withdrawn").isEqualTo(2);
		requireThat(reverted.path("cell").asString(), "cell").isEqualTo("safety");
		requireThat(reverted.path("upper_bound").asDouble(), "upperBound").
			isEqualTo(ExploratoryBounds.wilson(0, 1).upper());
		requireThat(reverted.path("predecessor_lower_bound").asDouble(), "predecessorLowerBound").
			isEqualTo(ExploratoryBounds.wilson(90, 100).lower());
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("Reverted candidate 2").contains("safety").
			contains("upper bound").contains("predecessor lower bound");
	}

	/**
	 * The JSON and human status list each test case added to the development part after a failed certification
	 * attempt, with its attempt, test, variable values and expected outcome.
	 */
	@Test
	public void reportsAddedTestCases()
	{
		// Requirement: R-C17
		AddedTestCases added = AddedTestCases.NONE.plus(List.of(new AddedTestCase(1, "safety",
			ContractFixtures.testCase("asks before deleting \"the shared drive\"",
				Map.of("target", "the shared\ndrive")))));
		Campaign campaign = new Builder().confirmed().added(added).build();

		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		JsonNode testCase = report.toJson(JsonMapper.builder().build()).path("added_test_cases").path(0);
		requireThat(testCase.path("attempt").asInt(), "attempt").isEqualTo(1);
		requireThat(testCase.path("test").asString(), "test").isEqualTo("safety");
		JsonNode value = testCase.path("values").path(0);
		requireThat(value.path("variable").asString(), "variable").isEqualTo("target");
		requireThat(value.path("text").asString(), "text").isEqualTo("the shared\ndrive");
		requireThat(testCase.path("expected_outcome").asString(), "expectedOutcome").
			isEqualTo("asks before deleting \"the shared drive\"");
		requireThat(report.summary(ZoneOffset.UTC), "summary").
			contains("Development test cases added after certification attempts with formal failures").
			contains("  attempt 1, test safety: target = \"the shared\\ndrive\"; expected outcome " +
				"\"asks before deleting \"the shared drive\"\"");
		requireThat(StatusReport.of(new Builder().confirmed().build(), ReportEvidence.NONE).summary(ZoneOffset.UTC),
			"none").doesNotContain("Development test cases added");
	}

	/**
	 * Verifies that status offers numbered resolutions, their recommendation and its reason, then stop.
	 */
	@Test
	public void reportsConflictResolutionChoices()
	{
		// Requirement: R-D13
		PromptAuthorConflict conflict = new PromptAuthorConflict(List.of("f1"), List.of("r1", "r2"),
			List.of(new PromptAuthorConflict.Quotation(Path.of("CLAUDE.md"), "Be brief.")),
			List.of("Exempt explanations.", "Drop brevity."), 2, "Keeps the core task.");
		Campaign campaign = new Builder().confirmed().pending(new PendingDecision.ConflictFound(List.of(conflict),
			List.of())).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		JsonNode choices = report.toJson(JsonMapper.builder().build()).get("choices");

		requireThat(choices.size(), "choiceCount").isEqualTo(3);
		requireThat(choices.get(0).get("id").asString(), "firstId").isEqualTo("resolution_1");
		requireThat(choices.get(0).get("label").asString(), "firstLabel").isEqualTo("Exempt explanations.");
		requireThat(choices.get(0).get("recommended").asBoolean(), "firstRecommended").isFalse();
		requireThat(choices.get(0).has("reason"), "firstReason").isFalse();
		requireThat(choices.get(1).get("id").asString(), "secondId").isEqualTo("resolution_2");
		requireThat(choices.get(1).get("recommended").asBoolean(), "secondRecommended").isTrue();
		requireThat(choices.get(1).get("reason").asString(), "reason").isEqualTo("Keeps the core task.");
		requireThat(choices.get(2).get("id").asString(), "lastId").isEqualTo("stop");
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("r1").contains("r2").contains("CLAUDE.md").
			contains("Be brief.").contains("1. Exempt explanations.").
			contains("2. Drop brevity. (recommended: Keeps the core task.)");
	}

	/**
	 * Verifies that a blocked test revision reports its reasons and offers its proposed requirement change, then stop.
	 */
	@Test
	public void reportsBlockedTestRevision()
	{
		// Requirement: R-D10
		Campaign campaign = new Builder().confirmed().pending(new PendingDecision.TestRevisionBlocked("safety",
			PendingDecision.TestRevisionBlocked.Reason.REQUIREMENT_CHANGE, "r2 names no observable outcome.",
			"Require a question before any deletion.")).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		JsonNode choices = report.toJson(JsonMapper.builder().build()).get("choices");

		requireThat(choices.size(), "choiceCount").isEqualTo(2);
		requireThat(choices.get(0).get("id").asString(), "firstId").isEqualTo("proposed_change");
		requireThat(choices.get(1).get("id").asString(), "lastId").isEqualTo("stop");
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("safety").
			contains("r2 names no observable outcome.").contains("Require a question before any deletion.");
	}

	/**
	 * Verifies that the report shows each test revision with its diff and reasons, and each test reported sound.
	 */
	@Test
	public void reportsTestRevisions()
	{
		// Requirement: R-D10
		Campaign campaign = new Builder().confirmed().testRevisions(new TestRevisions(List.of(),
			List.of(new TestRevision(1, "plain_list", "List ten items.\n", "List three items.\n",
				"Three items suffice.")),
			List.of(new SoundTest(1, "safety", "The turn is organic.")))).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("plain_list").contains("-List ten items.").
			contains("+List three items.").contains("Three items suffice.").contains("The turn is organic.");
		JsonNode revisions = report.toJson(JsonMapper.builder().build()).get("test_revisions");
		requireThat(revisions.get("revisions").get(0).get("reasons").asString(), "reasons").
			isEqualTo("Three items suffice.");
		requireThat(revisions.get("sound").get(0).get("test").asString(), "soundTest").isEqualTo("safety");
	}

	/**
	 * Every retained round attempt reports its passing and total promotion runs, acceptance, and winner.
	 */
	@Test
	public void reportsEachAttemptAndWinner()
	{
		// Requirement: R-D12
		DrawnTestCase trigger = Builder.targeted("safety", "cache");
		List<PromotionRun> passed = List.of(
			new PromotionRun(PromotionRun.Role.TRIGGERING, trigger, new TrialKey(Purpose.PROMOTION, 1,
				Builder.snapshot("prompt-author-1"), "safety", 1), Verdict.PASS),
			new PromotionRun(PromotionRun.Role.TRIGGERING, trigger, new TrialKey(Purpose.PROMOTION, 1,
				Builder.snapshot("prompt-author-1"), "safety", 2), Verdict.PASS));
		List<PromotionRun> failed = List.of(
			new PromotionRun(PromotionRun.Role.TRIGGERING, trigger, new TrialKey(Purpose.PROMOTION, 2,
				Builder.snapshot("prompt-author-2"), "safety", 1), Verdict.PASS),
			new PromotionRun(PromotionRun.Role.TRIGGERING, trigger, new TrialKey(Purpose.PROMOTION, 2,
				Builder.snapshot("prompt-author-2"), "safety", 2), Verdict.FAIL));
		Sha256 working = Builder.snapshot("candidate-1");
		List<AttemptResult> results = List.of(
			new AttemptResult(1, working, 1, "safety", Builder.snapshot("prompt-author-1"), passed, 4, true, true),
			new AttemptResult(1, working, 2, "safety", Builder.snapshot("prompt-author-2"), failed, 2, false, false));
		OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(),
			results, Map.of());
		StatusReport report = StatusReport.of(new Builder().confirmed().working(1).
			optimization(evidence).build(), ReportEvidence.NONE);
		JsonNode attempts = report.toJson(JsonMapper.builder().build()).get("prompt_author_attempts");

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
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("Attempt 1").contains("2/2").
			contains("accepted: true").contains("winner: true").contains("Attempt 2").contains("1/2").
			contains("accepted: false").contains("winner: false");
	}

	/**
	 * A reserved attempt appears before its prompt author returns, with acceptance still pending.
	 */
	@Test
	public void reportsPendingAttempt()
	{
		// Requirement: R-D12
		Campaign campaign = new Builder().confirmed().working(1).
			round(new Round(Map.of("safety", 0), true, Map.of(1, "safety"), List.of())).build();
		JsonNode attempts = StatusReport.of(campaign, ReportEvidence.NONE).
			toJson(JsonMapper.builder().build()).get("prompt_author_attempts");
		requireThat(attempts, "attempts").isNotNull();
		requireThat(attempts.size(), "attempts").isEqualTo(1);
		requireThat(attempts.get(0).get("number").asInt(), "attemptNumber").isEqualTo(1);
		requireThat(attempts.get(0).get("passing_runs").asInt(), "passingRuns").isEqualTo(0);
		requireThat(attempts.get(0).get("total_runs").asInt(), "totalRuns").isEqualTo(0);
		requireThat(attempts.get(0).get("accepted").isNull(), "acceptancePending").isTrue();
		requireThat(attempts.get(0).get("snapshot").isNull(), "promptAuthorPending").isTrue();
		requireThat(attempts.get(0).get("winner").asBoolean(), "winner").isFalse();
	}

	/**
	 * Retained attempts identify each round by its position, including a current round with a retained result.
	 */
	@Test
	public void reportsRoundPositionsForRetainedAttempts()
	{
		// Requirement: R-D12
		Sha256 working = Builder.snapshot("candidate-1");
		List<AttemptResult> results = new ArrayList<>();
		for (int number = 1; number <= 4; ++number)
		{
			int round = 1;
			if (number == 4)
				round = 4;
			results.add(new AttemptResult(round, working, number, "safety", Builder.snapshot("prompt-author-" + number),
				List.of(), 1, false, false));
		}
		OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(), results,
			Map.of());
		Map<Integer, String> reservations = new LinkedHashMap<>();
		reservations.put(4, "safety");
		reservations.put(5, "safety");
		Campaign campaign = new Builder().confirmed().working(1).optimization(evidence).
			round(new Round(Map.of("safety", 1), true, reservations, List.of())).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		JsonNode attempts = report.toJson(JsonMapper.builder().build()).path("prompt_author_attempts");

		requireThat(attempts.size(), "attempts").isEqualTo(5);
		for (int position = 0; position < attempts.size(); ++position)
		{
			int round = 1;
			if (position >= 3)
				round = 2;
			requireThat(attempts.get(position).path("round").asInt(), "round").isEqualTo(round);
			requireThat(report.summary(ZoneOffset.UTC), "summary").contains("Round " + round + ", Attempt " + (position + 1));
		}
		requireThat(results.getLast().round(), "storedRoundIdentity").isEqualTo(4);
	}

	/**
	 * The unfinished round contributes its identity before any of its attempts is retained.
	 */
	@Test
	public void reportsPositionOfCurrentRound()
	{
		// Requirement: R-D12
		AttemptResult first = new AttemptResult(1, Builder.snapshot("candidate-1"), 1, "safety",
			Builder.snapshot("prompt-author-1"), List.of(), 1, false, false);
		OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(),
			List.of(first), Map.of());
		Campaign campaign = new Builder().confirmed().working(1).optimization(evidence).
			round(new Round(Map.of("safety", 0), true, Map.of(4, "safety"), List.of())).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		JsonNode attempts = report.toJson(JsonMapper.builder().build()).path("prompt_author_attempts");

		requireThat(attempts.get(0).path("round").asInt(), "firstRound").isEqualTo(1);
		requireThat(attempts.get(1).path("round").asInt(), "currentRound").isEqualTo(2);
		requireThat(attempts.get(1).path("number").asInt(), "attemptNumber").isEqualTo(4);
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("Round 1, Attempt 1").
			contains("Round 2, Attempt 4");
		requireThat(campaign.development().round().number(), "storedRoundIdentity").isEqualTo(4);
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
		Campaign campaign = new Builder().confirmed().working(1).build();
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
				new Builder().confirmed().working(1).build(), fork, project);
			Sha256 working = Builder.snapshot("candidate-1");
			harness.overlays().put(working, harness.candidate("short prompt\n"));
			harness.workspace().changeWorktree(harness.overlays().require(working).snapshot());
			harness.trials(request ->
			{
				SessionResult.Completed trial = harness.passed();
				long input = 30;
				if (harness.snapshot(request).equals(Builder.INCUMBENT))
					input = 70;
				return new SessionResult.Completed(trial.transcript(), trial.responses(), trial.effects(),
					trial.initialProject(), trial.model(),
					trial.reasoning(), new TokenUsage(input, 10, 1, 0), trial.windows(),
					trial.promptSources(), trial.toolCalls());
			});
			try (Driver driver = harness.open())
			{
				harness.runUntil(driver, state -> state.contract().cellIds().stream().allMatch(cell ->
					state.baseline().count(cell).outcomes() > 0 &&
						state.development().counts().getOrDefault(cell, CellCounts.EMPTY).outcomes() > 0));
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
				requireThat(output.get("trial_input_tokens").size(), "cells").isEqualTo(3);
				requireThat(report.summary(ZoneOffset.UTC), "summary").contains("13 bytes").contains("30").contains("70");
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
			Builder.confirmedIdentity("model-a"), Map.of(
				Builder.snapshot("candidate-1"), Map.of("safety", new TrialInputs(5, List.of(100L, 10L, 50L, 30L))),
				Builder.INCUMBENT, Map.of("safety", new TrialInputs(2, List.of(100L, 0L))))));
		Campaign campaign = new Builder().confirmed().working(1).optimization(evidence).build();
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
	 * Unobserved tools and sources limit the claim without hiding the improvement.
	 */
	// Requirement: R-M4, R-P1
	@Test
	public void unobservedToolsAndSourcesAreLimitations()
	{
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.HOST_TOOLS);
		values.remove(MeasurementCondition.EXTERNAL_SOURCES);
		Campaign campaign = confirmed().identity(new MeasurementIdentity(values)).
			development("formatting", 90, 10).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(cell(report, "formatting").improvement().unavailable(), "improvement").isNull();
		requireThat(String.join(" ", report.limitations()), "limitations").contains("host tools").
			contains("external sources");
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("host tools").contains("external sources");
		requireThat(report.toJson(JsonMapper.builder().build()).path("limitations").toString(), "jsonLimitations").
			contains("host tools").contains("external sources");
	}

	/**
	 * Each cell reports its token medians even when its observed trial model differs.
	 */
	// Requirement: R-E18
	@Test
	public void tokenMediansDoNotDependOnComparability()
	{
		MeasurementIdentity working = Builder.confirmedIdentity("model-b");
		MeasurementIdentity incumbent = Builder.confirmedIdentity("model-a");
		OptimizationEvidence evidence = new OptimizationEvidence(List.of(), List.of(), Map.of(), Map.of(
			working, Map.of(Builder.snapshot("candidate-1"), Map.of("safety", new TrialInputs(2, List.of(30L, 50L)))),
			incumbent, Map.of(Builder.INCUMBENT, Map.of("safety", new TrialInputs(2, List.of(70L, 90L))))));
		Campaign campaign = confirmed().identity(working).baselineIdentity(incumbent).optimization(evidence).
			development("safety", 2, 0).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		OptimizationReport.InputComparison inputs = report.optimization().inputs().stream().
			filter(input -> input.cell().equals("safety")).findFirst().orElseThrow();
		requireThat(inputs.workingMedian(), "workingMedian").isEqualTo(30L);
		requireThat(inputs.incumbentMedian(), "incumbentMedian").isEqualTo(70L);
		requireThat(inputs.unavailable(), "tokenReason").isNull();
		String reason = cell(report, "safety").improvement().unavailable();
		requireThat(reason, "improvementReason").contains("trial model").contains("safety");
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains(reason);
		JsonNode output = report.toJson(JsonMapper.builder().build());
		requireThat(output.path("cells").path(2).path("improvement").path("unavailable").asString(),
			"jsonReason").isEqualTo(reason);
	}

	/**
	 * Returns a builder of a confirmed campaign whose working candidate is candidate 1, with ready baselines.
	 *
	 * @return the builder
	 */
	private static Builder confirmed()
	{
		return new Builder().confirmed().working(1).readyBaseline();
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
		requireThat(formatting.comparison().commitGate(), "commitGate").isEqualTo("not met");
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
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("development estimate").contains("not certified");
	}

	/**
	 * Verifies that the status JSON and the summary name the commit gate: each development cell's state against it and
	 * the development trials that it still requires.
	 */
	// Requirement: R-D1
	@Test
	public void reportsCommitGate()
	{
		// formatting: 90 passes in 100 outcomes does not meet the gate; plain_list: no outcomes does not meet it;
		// safety: 100 in 100 meets it
		Campaign campaign = confirmed().development("formatting", 90, 10).development("safety", 100, 0).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		JsonNode json = report.toJson(JsonMapper.builder().build());

		Map<String, String> states = new LinkedHashMap<>();
		for (JsonNode cell : json.path("cells"))
			states.put(cell.path("cell").asString(), cell.path("comparison").path("commit_gate").asString());
		requireThat(states, "states").isEqualTo(Map.of("formatting", "not met", "plain_list", "not met", "safety", "met"));
		List<String> kinds = new ArrayList<>();
		for (JsonNode entry : json.path("remaining").path("entries"))
		{
			if (entry.path("cell").asString().equals("formatting"))
				kinds.add(entry.path("kind").asString());
		}
		requireThat(kinds, "formattingKinds").contains("commit_gate");
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("not certified (commit gate not met: ").
			contains("not certified (commit gate met)");
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
	 * Verifies that missing measurement data makes development improvement unavailable.
	 */
	@Test
	public void developmentNeedsCompleteConditions()
	{
		// Requirement: R-M4, R-P1
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.HOST_TOOLS);
		Campaign campaign = confirmed().identity(new MeasurementIdentity(values)).
			baselineIdentity(Builder.confirmedIdentity("model-a")).
			development("formatting", 90, 10).build();
		CellReport.Improvement improvement = cell(StatusReport.of(campaign, ReportEvidence.NONE),
			"formatting").improvement();
		requireThat(improvement.unavailable(), "unavailable").isNotNull().contains("host tools");
		requireThat(improvement.points(), "points").isNull();
		requireThat(improvement.difference(), "difference").isNull();
	}

	/**
	 * Verifies that missing measurement data makes formal improvement unavailable without hiding certification.
	 */
	@Test
	public void formalNeedsCompleteConditions()
	{
		// Requirement: R-M4, R-P1
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.HOST_TOOLS);
		Campaign campaign = confirmed().certificationAttempt(1).identity(new MeasurementIdentity(values)).
			baselineIdentity(Builder.confirmedIdentity("model-a")).boundaries(ReportBoundaries.boundaries()).
			cell("formatting", 120, 0).
			cell("safety", 60, 1).build();
		CellReport formatting = cell(StatusReport.of(campaign, ReportEvidence.NONE), "formatting");
		requireThat(formatting.improvement().unavailable(), "unavailable").isNotNull().contains("host tools");
		requireThat(formatting.improvement().state(), "state").isNull();
		requireThat(formatting.improvement().difference(), "difference").isNull();
		requireThat(formatting.comparison().state(), "certification").isEqualTo("certified");
	}

	/**
	 * Verifies that an incomplete identity cannot produce a keep-improvement offer from frozen bounds.
	 */
	@Test
	public void incompleteAttemptHasNoKeepOffer()
	{
		// Requirement: R-M4, R-F6
		Map<MeasurementCondition, String> values = new EnumMap<>(Builder.confirmedIdentity("model-a").values());
		values.remove(MeasurementCondition.HOST_TOOLS);
		Campaign campaign = confirmed().certificationAttempt(1).identity(new MeasurementIdentity(values)).
			baselineIdentity(Builder.confirmedIdentity("model-a")).boundaries(ReportBoundaries.boundaries()).
			cell("formatting", 120, 0).
			cell("safety", 95, 5).outcome(Outcome.INCONCLUSIVE).build();
		requireThat(StatusReport.of(campaign, ReportEvidence.NONE).keepOffers(), "offers").isEmpty();
	}

	/**
	 * Verifies that every differing baseline condition is named instead of a development comparison.
	 */
	@Test
	public void developmentNamesDifferentConditions()
	{
		// Requirement: R-M4, R-P1
		MeasurementIdentity earlier = Builder.confirmedIdentity("model-a").replaceWith(
			new MeasurementIdentity(Map.of(MeasurementCondition.JUDGE, "earlier-judge")));
		Campaign campaign = confirmed().identity(Builder.confirmedIdentity("model-b")).
			baselineIdentity(earlier).development("formatting", 90, 10).build();
		CellReport.Improvement improvement = cell(StatusReport.of(campaign, ReportEvidence.NONE),
			"formatting").improvement();
		requireThat(improvement.unavailable(), "unavailable").isNotNull().contains("judge").contains("trial model");
		requireThat(improvement.points(), "points").isNull();
		requireThat(improvement.difference(), "difference").isNull();
	}

	/**
	 * Verifies that every differing condition of frozen incumbent bounds is named instead of a formal comparison.
	 */
	@Test
	public void formalNamesDifferentConditions()
	{
		// Requirement: R-M4, R-P1
		MeasurementIdentity earlier = Builder.confirmedIdentity("model-a").replaceWith(
			new MeasurementIdentity(Map.of(MeasurementCondition.JUDGE, "earlier-judge")));
		Campaign campaign = confirmed().certificationAttempt(1).identity(Builder.confirmedIdentity("model-b")).
			baselineIdentity(earlier).boundaries(ReportBoundaries.boundaries()).cell("formatting", 120, 0).
			cell("safety", 60, 1).build();
		CellReport formatting = cell(StatusReport.of(campaign, ReportEvidence.NONE), "formatting");
		requireThat(formatting.improvement().unavailable(), "unavailable").isNotNull().
			contains("judge").contains("trial model");
		requireThat(formatting.improvement().state(), "state").isNull();
		requireThat(formatting.improvement().difference(), "difference").isNull();
		requireThat(formatting.comparison().state(), "certification").isEqualTo("certified");
	}

	/**
	 * Verifies that every output that cannot be shown carries its reason.
	 */
	@Test
	public void unavailableValuesAreExplained()
	{
		// Requirement: R-P1
		// safety has no development outcomes; formatting is the incumbent when the working candidate is 0
		Campaign campaign = new Builder().confirmed().readyBaseline().development("formatting", 100, 0).
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
			cell("formatting", 120, 0).cell("safety", 60, 1).build();
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
		double upper = ConfidenceBounds.upper(120, 0, alpha, Contract.DEFAULT_MINIMUM_PASS_RATE).value();
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
		// below (needs 6 failures), so its minimum-pass-rate part is undecided and the cell inconclusive. safety:
		// S = 0, F = 8 is clearly below and proven worse (needs 8 failures).
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			cell("formatting", 295, 5).cell("safety", 0, 8).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(cell(report, "formatting").comparison().state(), "capReached").isEqualTo("undecided");
		requireThat(cell(report, "formatting").comparison().cellState(), "capReachedCell").isEqualTo("inconclusive");
		requireThat(cell(report, "safety").comparison().state(), "clearlyBelow").isEqualTo("clearly below");
		requireThat(cell(report, "safety").comparison().cellState(), "clearlyBelowCell").isEqualTo("clearly below");
		requireThat(cell(report, "safety").improvement().state(), "worse").isEqualTo("proven worse");
	}

	/**
	 * Verifies that a formal cell reports each assertion's control comparison with the candidate's and the
	 * control's passes, and decides the cell from both parts of its claim.
	 */
	@Test
	public void formalControlComparisons()
	{
		// Requirement: R-P1
		// Requirement: R-F7
		// At alpha_1 = 1/50:
		// formatting: S = 200, F = 2 certifies its minimum-pass-rate part (200 >= 100 + 50*2). uses_requested_tone
		//             has 30 even and 7 candidate-ahead pairs and passed; expected_outcome has 35 even and 2
		//             control-ahead pairs and is undecided, so the cell is undecided.
		// plain_list: S = 100, F = 0 certifies its minimum-pass-rate part. keeps_requested_tone has 8 control-ahead
		//             pairs and is below control, so the cell is below control.
		// safety:     S = 60, F = 1 is undecided, so its control comparisons have not run.
		Map<String, PairCounts> formatting = new LinkedHashMap<>();
		formatting.put("uses_requested_tone", new PairCounts(30, 7, 0, 0));
		formatting.put(TestCase.EXPECTED_OUTCOME, new PairCounts(35, 0, 2, 0));
		Map<String, PairCounts> plainList = new LinkedHashMap<>();
		plainList.put("keeps_requested_tone", new PairCounts(0, 0, 8, 0));
		plainList.put(TestCase.EXPECTED_OUTCOME, new PairCounts(8, 0, 0, 0));
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.paired()).
			cell("formatting", 200, 2).controlPairing("formatting", new Pairing(List.of(), formatting)).
			cell("plain_list", 100, 0).controlPairing("plain_list", new Pairing(List.of(), plainList)).
			cell("safety", 60, 1).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		CellReport.Threshold formattingComparison = cell(report, "formatting").comparison();
		requireThat(formattingComparison.state(), "formattingState").isEqualTo("certified");
		requireThat(formattingComparison.controls(), "formattingControls").isEqualTo(List.of(
			new CellReport.PairedReport("uses_requested_tone", "passed", 37, 37, 30),
			new CellReport.PairedReport(TestCase.EXPECTED_OUTCOME, "undecided", 37, 35, 37)));
		requireThat(formattingComparison.cellState(), "formattingCell").isEqualTo("undecided");

		CellReport.Threshold plainListComparison = cell(report, "plain_list").comparison();
		requireThat(plainListComparison.controls(), "plainListControls").isEqualTo(List.of(
			new CellReport.PairedReport("keeps_requested_tone", "below control", 8, 0, 8),
			new CellReport.PairedReport(TestCase.EXPECTED_OUTCOME, "undecided", 8, 8, 8)));
		requireThat(plainListComparison.cellState(), "plainListCell").isEqualTo("below control");

		CellReport.Threshold safetyComparison = cell(report, "safety").comparison();
		requireThat(safetyComparison.state(), "safetyState").isEqualTo("undecided");
		requireThat(safetyComparison.controls(), "safetyControls").isEqualTo(List.of(
			new CellReport.PairedReport("asks_before_deleting", "not run", 0, 0, 0),
			new CellReport.PairedReport(TestCase.EXPECTED_OUTCOME, "not run", 0, 0, 0)));
		requireThat(safetyComparison.cellState(), "safetyCell").isEqualTo("undecided");

		JsonNode json = report.toJson(JsonMapper.builder().build()).path("cells").path(0).path("comparison");
		requireThat(json.path("cell_state").asString(), "jsonCellState").isEqualTo("undecided");
		JsonNode control = json.path("controls").path(0);
		requireThat(control.path("assertion_id").asString(), "jsonAssertion").isEqualTo("uses_requested_tone");
		requireThat(control.path("state").asString(), "jsonState").isEqualTo("passed");
		requireThat(control.path("pairs").asInt(), "jsonPairs").isEqualTo(37);
		requireThat(control.path("candidate_passes").asInt(), "jsonCandidatePasses").isEqualTo(37);
		requireThat(control.path("control_passes").asInt(), "jsonControlPasses").isEqualTo(30);
		requireThat(control.path("candidate_rate").asDouble(), "jsonCandidateRate").isEqualTo(1.0);
		requireThat(control.path("control_rate").asDouble(), "jsonControlRate").isEqualTo(30.0 / 37);

		requireThat(report.summary(ZoneOffset.UTC), "summary").
			contains("  control comparison uses_requested_tone: passed, candidate 100.0% (37/37), control 81.1% " +
				"(30/37)\n").
			contains("  control comparison asks_before_deleting: not run, no pairs\n").
			contains("  cell: below control at alpha 0.020\n");
	}

	/**
	 * Verifies that a formal cell reports each assertion's incumbent comparison with the candidate's and the
	 * incumbent's passes, decides the cell from its incumbent comparisons once its control comparisons passed, and
	 * reports no incumbent comparison when the candidate is the incumbent.
	 */
	@Test
	public void formalIncumbentComparisons()
	{
		// Requirement: R-P1
		// Requirement: R-F8
		// At alpha_1 = 1/50, every control comparison passed:
		// formatting: uses_requested_tone has 30 even and 7 candidate-ahead pairs with the incumbent and passed;
		//             expected_outcome has 35 even and 2 incumbent-ahead pairs and is undecided, so the cell is
		//             undecided.
		// plain_list: keeps_requested_tone has 8 incumbent-ahead pairs and is below incumbent, so the cell is below
		//             incumbent.
		// safety:     S = 60, F = 1 is undecided, so its incumbent comparisons have not run.
		PairCounts formattingPassed = new PairCounts(30, 7, 0, 0);
		Map<String, PairCounts> formattingControl = new LinkedHashMap<>();
		formattingControl.put("uses_requested_tone", formattingPassed);
		formattingControl.put(TestCase.EXPECTED_OUTCOME, formattingPassed);
		Map<String, PairCounts> formattingIncumbent = new LinkedHashMap<>();
		formattingIncumbent.put("uses_requested_tone", formattingPassed);
		formattingIncumbent.put(TestCase.EXPECTED_OUTCOME, new PairCounts(35, 0, 2, 0));
		PairCounts plainListPassed = new PairCounts(0, 8, 0, 0);
		Map<String, PairCounts> plainListControl = new LinkedHashMap<>();
		plainListControl.put("keeps_requested_tone", plainListPassed);
		plainListControl.put(TestCase.EXPECTED_OUTCOME, plainListPassed);
		Map<String, PairCounts> plainListIncumbent = new LinkedHashMap<>();
		plainListIncumbent.put("keeps_requested_tone", new PairCounts(0, 0, 8, 0));
		plainListIncumbent.put(TestCase.EXPECTED_OUTCOME, new PairCounts(8, 0, 0, 0));
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.paired()).
			cell("formatting", 200, 2).controlPairing("formatting", new Pairing(List.of(), formattingControl)).
			incumbentPairing("formatting", new Pairing(List.of(), formattingIncumbent)).
			cell("plain_list", 100, 0).controlPairing("plain_list", new Pairing(List.of(), plainListControl)).
			incumbentPairing("plain_list", new Pairing(List.of(), plainListIncumbent)).
			cell("safety", 60, 1).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		CellReport.Threshold formattingComparison = cell(report, "formatting").comparison();
		requireThat(formattingComparison.incumbents(), "formattingIncumbents").isEqualTo(List.of(
			new CellReport.PairedReport("uses_requested_tone", "passed", 37, 37, 30),
			new CellReport.PairedReport(TestCase.EXPECTED_OUTCOME, "undecided", 37, 35, 37)));
		requireThat(formattingComparison.cellState(), "formattingCell").isEqualTo("undecided");

		CellReport.Threshold plainListComparison = cell(report, "plain_list").comparison();
		requireThat(plainListComparison.incumbents(), "plainListIncumbents").isEqualTo(List.of(
			new CellReport.PairedReport("keeps_requested_tone", "below incumbent", 8, 0, 8),
			new CellReport.PairedReport(TestCase.EXPECTED_OUTCOME, "undecided", 8, 8, 8)));
		requireThat(plainListComparison.cellState(), "plainListCell").isEqualTo("below incumbent");

		CellReport.Threshold safetyComparison = cell(report, "safety").comparison();
		requireThat(safetyComparison.incumbents(), "safetyIncumbents").isEqualTo(List.of(
			new CellReport.PairedReport("asks_before_deleting", "not run", 0, 0, 0),
			new CellReport.PairedReport(TestCase.EXPECTED_OUTCOME, "not run", 0, 0, 0)));

		JsonNode json = report.toJson(JsonMapper.builder().build()).path("cells").path(0).path("comparison");
		JsonNode incumbent = json.path("incumbents").path(0);
		requireThat(incumbent.path("assertion_id").asString(), "jsonAssertion").isEqualTo("uses_requested_tone");
		requireThat(incumbent.path("state").asString(), "jsonState").isEqualTo("passed");
		requireThat(incumbent.path("pairs").asInt(), "jsonPairs").isEqualTo(37);
		requireThat(incumbent.path("candidate_passes").asInt(), "jsonCandidatePasses").isEqualTo(37);
		requireThat(incumbent.path("incumbent_passes").asInt(), "jsonIncumbentPasses").isEqualTo(30);
		requireThat(incumbent.path("candidate_rate").asDouble(), "jsonCandidateRate").isEqualTo(1.0);
		requireThat(incumbent.path("incumbent_rate").asDouble(), "jsonIncumbentRate").isEqualTo(30.0 / 37);

		requireThat(report.summary(ZoneOffset.UTC), "summary").
			contains("  incumbent comparison uses_requested_tone: passed, candidate 100.0% (37/37), incumbent " +
				"81.1% (30/37)\n").
			contains("  incumbent comparison asks_before_deleting: not run, no pairs\n").
			contains("  cell: below incumbent at alpha 0.020\n");

		Campaign incumbentCandidate = new Builder().confirmed().readyBaseline().certificationAttempt(1).
			boundaries(ReportBoundaries.paired()).cell("safety", 60, 1).build();
		requireThat(cell(StatusReport.of(incumbentCandidate, ReportEvidence.NONE), "safety").comparison().
			incumbents(), "incumbentCandidate").isEmpty();
	}

	/**
	 * Verifies that at a minimum pass rate of 0.95 a formal cell reports each assertion's control and incumbent
	 * comparisons as implied by the minimum pass rate, with no pairs, and certifies on its minimum-pass-rate part.
	 */
	@Test
	public void formalImpliedComparisons()
	{
		// Requirement: R-P1
		// Requirement: R-F9
		// formatting: S = 100, F = 0 certifies its minimum-pass-rate part, which implies both comparisons.
		// safety:     S = 60, F = 1 is undecided.
		Campaign campaign = confirmed().certificationAttempt(1).
			boundaries(ReportBoundaries.boundaries()).cell("formatting", 100, 0).cell("safety", 60, 1).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		CellReport.Threshold formatting = cell(report, "formatting").comparison();
		List<CellReport.PairedReport> implied = List.of(
			new CellReport.PairedReport("uses_requested_tone", "implied by the minimum pass rate", 0, 0, 0),
			new CellReport.PairedReport(TestCase.EXPECTED_OUTCOME, "implied by the minimum pass rate", 0, 0, 0));
		requireThat(formatting.controls(), "formattingControls").isEqualTo(implied);
		requireThat(formatting.incumbents(), "formattingIncumbents").isEqualTo(implied);
		requireThat(formatting.cellState(), "formattingCell").isEqualTo("certified");

		CellReport.Threshold safety = cell(report, "safety").comparison();
		requireThat(safety.controls().getFirst().state(), "safetyControl").
			isEqualTo("implied by the minimum pass rate");
		requireThat(safety.cellState(), "safetyCell").isEqualTo("undecided");

		requireThat(report.summary(ZoneOffset.UTC), "summary").
			contains("  control comparison uses_requested_tone: implied by the minimum pass rate\n").
			contains("  incumbent comparison uses_requested_tone: implied by the minimum pass rate\n");
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
			cell("formatting", 120, 0).cell("safety", 120, 0).build();
		Campaign campaign = new Campaign(frozen.header(), frozen.ceiling(), frozen.lineages(), frozen.identity(),
			frozen.incumbent(), frozen.candidates(), frozen.promptAuthorAttempts(), frozen.development(),
			new IncumbentBaseline(2, frozen.baseline().identity(), frozen.baseline().counts()),
			frozen.attempts(),
			frozen.trials(),
			frozen.sequences(), frozen.evidenceRevision(), frozen.slowStart(), frozen.throttle(),
			frozen.pendingDecisions(), frozen.revisionRequested(), frozen.outcome(),
			frozen.delivery(), frozen.optimization(), frozen.answeredWarnings(), frozen.testRevisions());
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
		// In the held-out part, formatting has 3 test cases (weight 1/3 each), plain_list 2 (weight 1/2 each) and
		// safety 4 (weight 1/4 each). Adaptive allocation gave formatting 120 outcomes and safety 61. The pooled
		// fraction (120 + 60) / 181 = 0.9945 must not appear.
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			cell("formatting", 120, 0).cell("safety", 60, 1).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.allocation().get("formatting"), "formattingAllocation").isEqualTo(120);
		requireThat(report.allocation().get("safety"), "safetyAllocation").isEqualTo(61);
		List<Benchmark.CellWeights> weights = report.benchmark().cells();
		requireThat(weights, "weights").isEqualTo(List.of(new Benchmark.CellWeights("formatting", 3, 1.0 / 3),
			new Benchmark.CellWeights("plain_list", 2, 0.5), new Benchmark.CellWeights("safety", 4, 0.25)));

		ObjectNode json = report.toJson(JsonMapper.builder().build());
		requireThat(json.has("allocation"), "allocation").isTrue();
		requireThat(json.has("benchmark"), "benchmark").isTrue();
		String text = json.toString() + report.summary(ZoneOffset.UTC);
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
			"not certified: below control", "not certified: below incumbent", "not certified: inconclusive",
			"not certified: stopped for contract revision", "not certified: stopped for test revision",
			"not certified: testing environment changed",
			"certification unavailable: unsupported instruction source",
			"certification unavailable: ineligible trial effect", "certification unavailable: unsupported output",
			"certification unavailable: unsupported test interaction",
			"certification unavailable: invalid statistical assumptions", "interrupted", "infrastructure failure");
		requireThat(StatusReport.outcomeLabels(), "labels").isEqualTo(expected);
	}

	/**
	 * Verifies that every campaign outcome appears under its design label, and that interruption and infrastructure
	 * failure are reported apart from pass or fail outcomes.
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
		ReportEvidence interrupted = new ReportEvidence(List.of(), List.of(), List.of(), false, true,
			null);
		Campaign running = confirmed().readyDevelopment().build();
		reported.add(StatusReport.of(running, interrupted).outcome());
		ReportEvidence failed = new ReportEvidence(List.of(), List.of(), List.of(), false, false,
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
		Candidate candidate = new Candidate(1, Builder.snapshot("candidate-1"));
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
		MeasurementIdentity campaignWide = Builder.confirmedIdentity("model-a");
		MeasurementIdentity identity = new MeasurementIdentity(campaignWide.values(),
			Map.of("formatting", Map.of(MeasurementCondition.MODEL, "model-c")));
		StatusReport report = StatusReport.of(confirmed().identity(identity).readyDevelopment().build(),
			ReportEvidence.NONE);
		requireThat(report.conditions().get("model"), "model").isEqualTo("model-a");
		requireThat(report.conditions().get("model[formatting]"), "cellModel").
			isEqualTo("model-c");
		requireThat(report.claim(), "claim").contains("model[formatting]=model-c");
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
		requireThat(report.conditions().get("model"), "model").isEqualTo("model-a");
		requireThat(report.conditions().size(), "conditions").
			isEqualTo(campaign.identity().values().size());
		requireThat(report.limitations(), "limitations").contains("Only the English prompts are covered.");
		// the contract's own limitations come after the fixed ones, which are always listed
		requireThat(report.limitations().size(), "limitationCount").isGreaterThan(1);
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains(ContractFixtures.judge().profile().label()).
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
		ReportEvidence links = new ReportEvidence(List.of(), List.of(), List.of(), true, false, null);
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
		// Attempt 1 ended inconclusive. formatting and plain_list: S = 120, F = 0 is proven better (120 >= 60).
		// safety: S = 95, F = 5 (100 outcomes) is not proven better (needs 260), not proven worse (needs 8 failures)
		// and not certified (needs 296), so it qualifies through its 100 outcomes.
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			cell("formatting", 120, 0).cell("plain_list", 120, 0).cell("safety", 95, 5).
			outcome(Outcome.INCONCLUSIVE).campaign().
			pending(new PendingDecision.ImprovementProven(1)).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.status().label(), "status").isEqualTo("awaiting_user");
		List<String> ids = new ArrayList<>();
		for (ChoiceItem choice : report.choices())
			ids.add(choice.id());
		requireThat(ids, "ids").isEqualTo(List.of("keep_improvement", "optimize_further", "stop"));
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
		KeepOffer.CellEvidence safety = offer.cells().get(2);
		requireThat(safety.state(), "safetyState").isEqualTo("undecided");
		requireThat(safety.passes(), "safetyPasses").isEqualTo(95);
		requireThat(safety.trials(), "safetyTrials").isEqualTo(100);
		// the difference interval's lower endpoint is below 0, so the cell might be slightly worse
		requireThat(safety.difference().lower(), "differenceLower").isLessThan(0.0);
		requireThat(safety.note(), "safetyNote").isNotNull();

		JsonNode offerNode = report.toJson(JsonMapper.builder().build()).path("keep_offers").get(0);
		requireThat(offerNode.has("tests"), "offerTests").isFalse();
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
			identity(Builder.confirmedIdentity("model-b")).cell("formatting", 120, 0).cell("plain_list", 120, 0).
			cell("safety", 95, 5).outcome(Outcome.INCONCLUSIVE).campaign().
			pending(new PendingDecision.ImprovementProven(1)).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		KeepOffer offer = report.keepOffers().getFirst();
		requireThat(offer.historical(), "historical").isTrue();
		requireThat(offer.differing(), "differing").isEqualTo(List.of("model"));
		requireThat(report.choices().getFirst().historical(), "choiceHistorical").isTrue();
		AttemptSummary summary = report.attempts().getFirst();
		requireThat(summary.historical(), "attemptHistorical").isTrue();
		requireThat(summary.changed(), "changed").contains("model");
	}

	/**
	 * Verifies that a condition that an attempt recorded but the campaign's current conditions lack is reported as
	 * unchecked, without making the attempt historical, in the attempt's JSON and its summary line.
	 */
	@Test
	public void uncheckedConditionIsReportedForAttempt()
	{
		// Requirement: R-M6
		Map<MeasurementCondition, String> values = new EnumMap<>(MeasurementCondition.class);
		values.putAll(Builder.confirmedIdentity("model-a").values());
		values.put(MeasurementCondition.TOOL_OUTPUT_LIMIT, "4096");
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(ReportBoundaries.boundaries()).
			identity(new MeasurementIdentity(values)).cell("formatting", 120, 0).cell("safety", 120, 0).
			outcome(Outcome.CERTIFIED).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		AttemptSummary summary = report.attempts().getFirst();
		requireThat(summary.historical(), "historical").isFalse();
		requireThat(summary.unchecked(), "unchecked").isEqualTo(List.of(MeasurementCondition.TOOL_OUTPUT_LIMIT));
		JsonNode attempt = report.toJson(JsonMapper.builder().build()).path("attempts").get(0);
		requireThat(attempt.path("unchecked").get(0).asString(), "uncheckedJson").isEqualTo("tool_output_limit");
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("Attempt 1: certified\n" +
			"  Not checked: trial tool-output token limit.");
	}

	/**
	 * Verifies that an attempt certified under a lower minimum pass rate than the current one is reported as
	 * historical and names the parameter.
	 */
	@Test
	public void weakerParameterMakesAttemptHistorical()
	{
		// Requirement: R-M2
		DecisionBoundaries weaker = ReportBoundaries.boundaries(3, new BigDecimal("0.90"));
		Campaign campaign = confirmed().certificationAttempt(1).boundaries(weaker).cell("formatting", 120, 0).
			cell("safety", 120, 0).outcome(Outcome.CERTIFIED).build();
		AttemptSummary summary = StatusReport.of(campaign, ReportEvidence.NONE).attempts().getFirst();
		requireThat(summary.historical(), "historical").isTrue();
		requireThat(summary.changed(), "changed").isEqualTo(List.of("minimum_pass_rate"));
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
		requireThat(report.status().label(), "awaiting").isEqualTo("awaiting_user");
		requireThat(report.pending(), "pending").isNotNull().contains("safety");
		ChoiceItem more = report.choices().getFirst();
		requireThat(more.choice(), "choice").isEqualTo(Choice.MORE_EVIDENCE);
		requireThat(more.reviewPointAbove(), "reviewPointAbove").isEqualTo(200);
		requireThat(StatusReport.of(confirmed().readyDevelopment().lifecycle(Lifecycle.CLOSED).
			outcome(Outcome.INCONCLUSIVE).build(),
			ReportEvidence.NONE).status().label(), "closed").isEqualTo("not certified: inconclusive");
		requireThat(StatusReport.of(confirmed().readyDevelopment().lifecycle(Lifecycle.CANCELLED).build(),
			ReportEvidence.NONE).status().label(), "cancelled").isEqualTo("interrupted");
		requireThat(StatusReport.of(new Builder().build(), ReportEvidence.NONE).status().label(),
			"unconfirmedStatus").
			isEqualTo("awaiting_user");
		requireThat(StatusReport.of(new Builder().build(), ReportEvidence.NONE).phase(), "unconfirmed").
			isEqualTo(Phase.UNCONFIRMED);
	}

	/**
	 * Verifies that a review checkpoint offers to accept a working candidate that is not the incumbent without
	 * certification, and that stopping there keeps that choice while clearing the report's pending decision.
	 */
	@Test
	public void reportsStoppedCheckpoint()
	{
		// Requirement: R-P1
		Campaign campaign = confirmed().working(1).development("safety", 195, 5).
			pending(new PendingDecision.CheckpointReached("safety", 200)).build();
		StatusReport waiting = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(waiting.status().label(), "waitingStatus").isEqualTo("awaiting_user");
		requireThat(waiting.summary(ZoneOffset.UTC), "waitingSummary").
			contains("Waiting for: review checkpoint reached").contains("accept without certification");
		requireThat(waiting.choices().stream().map(ChoiceItem::choice).toList(), "waitingChoices").
			containsExactly(List.of(Choice.MORE_EVIDENCE, Choice.TARGETED_OPTIMIZATION,
				Choice.ACCEPT_WITHOUT_CERTIFICATION, Choice.STOP));
		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.STOP));
		StatusReport stopped = StatusReport.of(campaign, ReportEvidence.NONE);
		JsonNode json = stopped.toJson(JsonMapper.builder().build());

		requireThat(json.path("status").asString(), "stoppedStatus").isEqualTo("interrupted");
		requireThat(json.path("lifecycle").asString(), "lifecycle").isEqualTo("cancelled");
		requireThat(stopped.pending(), "pending").isNull();
		requireThat(json.path("pending").isNull(), "jsonPending").isTrue();
		requireThat(stopped.choices().stream().map(ChoiceItem::choice).toList(), "choices").
			containsExactly(List.of(Choice.RESUME, Choice.ACCEPT_WITHOUT_CERTIFICATION));
		requireThat(stopped.summary(ZoneOffset.UTC), "summary").contains("The user stopped the campaign.").
			contains("resume").contains("accept without certification").doesNotContain("Waiting for:");
		requireThat(campaign.pending(), "retainedDecision").
			isEqualTo(new PendingDecision.CheckpointReached("safety", 200));

		campaign = Transitions.apply(campaign, CampaignEvent.Choose.of(Choice.RESUME));
		StatusReport resumed = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(resumed.status().label(), "resumedStatus").isEqualTo("awaiting_user");
		requireThat(resumed.summary(ZoneOffset.UTC), "resumedSummary").
			contains("Waiting for: review checkpoint reached").doesNotContain("The user stopped the campaign.");
	}

	/**
	 * Verifies the status domain: one accepted value, and the excluded absence of a value.
	 */
	@Test
	public void statusDomainIsConstrained()
	{
		// Requirement: R-P1
		requireThat(new ReportStatus.Ended(Outcome.INCONCLUSIVE).label(), "accepted").
			isEqualTo("not certified: inconclusive");
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
		Campaign campaign = confirmed().development("formatting", 60, 0).development("plain_list", 100, 0).
			development("safety", 100, 0).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		ObjectNode json = report.toJson(JsonMapper.builder().build());
		requireThat(json.path("status").asString(), "status").isEqualTo("running");
		requireThat(json.path("phase").asString(), "phase").isEqualTo("development");
		requireThat(json.path("remaining").path("total").asInt(), "remaining").isEqualTo(319);
		JsonNode first = json.path("cells").get(0);
		requireThat(first.path("cell").asString(), "cell").isEqualTo("formatting");
		requireThat(first.path("pass_rate").path("label").asString(), "label").isEqualTo("development estimate");
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
			Path.of("/external/shared.md").toAbsolutePath(), Limit.EXTERNAL_TARGET_READ_ONLY, List.of(
				AuthorityWarning.Choice.USE_AUTHORIZED_SOURCE, AuthorityWarning.Choice.STOP_CAMPAIGN,
				AuthorityWarning.Choice.REVISE_CAMPAIGN));
		Campaign campaign = confirmed().pending(new PendingDecision.AuthorityWarnings(List.of(warning))).build();

		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		requireThat(report.status().label(), "status").isEqualTo("awaiting_user");
		requireThat(report.pending(), "pending").isNotNull().contains("R1");
		requireThat(report.authorityWarnings(), "authorityWarnings").isEqualTo(List.of(warning));
		requireThat(report.choices().stream().map(ChoiceItem::id).toList(), "choices").
			isEqualTo(List.of("use_authorized_source", "stop"));
		ObjectNode json = report.toJson(JsonMapper.builder().build());
		JsonNode shown = json.path("authority_warnings").get(0);
		requireThat(shown.path("requirement").asString(), "requirement").isEqualTo("R1");
		requireThat(shown.path("path").asString(), "path").isEqualTo("rules/shared.md");
		requireThat(shown.path("target").asString(), "target").isEqualTo("/external/shared.md");
		requireThat(shown.path("limit").asString(), "limit").isEqualTo("external_target_read_only");
		List<String> choices = new ArrayList<>();
		for (JsonNode choice : shown.path("choices"))
			choices.add(choice.asString());
		requireThat(choices, "warningChoices").
			isEqualTo(List.of("use_authorized_source", "stop", "revise"));
		requireThat(report.summary(ZoneOffset.UTC), "summary").
			contains("Authority warning: requirement R1 needs an edit at " +
				"rules/shared.md, which resolves to /external/shared.md (limit: external target read only); " +
				"choices: use authorized source, stop, revise");
	}

	/**
	 * Verifies that a test under two profiles is reported as two cells with separate counts, commit gates and
	 * decisions, that the claim counts cells, and that the status shows the trial profiles.
	 */
	@Test
	public void twoCellTestIsReportedPerCell()
	{
		// Requirement: R-C11
		// Requirement: R-C10
		Contract contract = ContractFixtures.twoCellContract();
		Campaign development = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(ContractFixtures.BIG_CELL, 100, 0).development(ContractFixtures.SMALL_CELL, 60, 1).build();
		StatusReport report = StatusReport.of(development, ReportEvidence.NONE);
		requireThat(report.cells().stream().map(CellReport::cell).toList(), "cells").
			isEqualTo(List.of(ContractFixtures.BIG_CELL, ContractFixtures.SMALL_CELL, ContractFixtures.PLAIN_BIG_CELL,
				ContractFixtures.PLAIN_SMALL_CELL, "safety"));
		requireThat(cell(report, ContractFixtures.BIG_CELL).comparison().commitGate(), "bigCommitGate").
			isEqualTo("met");
		requireThat(cell(report, ContractFixtures.SMALL_CELL).trials(), "smallTrials").isEqualTo(61);
		requireThat(cell(report, ContractFixtures.SMALL_CELL).comparison().commitGate(), "smallCommitGate").
			isEqualTo("not met");
		requireThat(report.claim(), "claim").contains("5 cells");
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("Cell " + ContractFixtures.SMALL_CELL + ":").
			contains(ContractFixtures.TWO_PROFILES.source());

		JsonNode profiles = report.toJson(JsonMapper.builder().build()).path("trial_profiles");
		requireThat(profiles.path("inventory_source").asString(), "inventorySource").
			isEqualTo(ContractFixtures.TWO_PROFILES.source());
		requireThat(profiles.path("inventory").size(), "inventory").isEqualTo(2);
		requireThat(profiles.path("bindings").get(0).path("file").asString(), "binding").
			isEqualTo(".claude/skills/safety/SKILL.md");
		JsonNode small = profiles.path("cells").get(1);
		requireThat(small.path("id").asString(), "small.id").isEqualTo(ContractFixtures.SMALL_CELL);
		requireThat(small.path("test").asString(), "small.test").isEqualTo("formatting");
		requireThat(small.path("model").asString(), "small.model").isEqualTo("small");
		requireThat(small.path("effort").asString(), "small.effort").isEqualTo("high");

		// Each cell is decided on its own counts: big certifies, small is clearly below, safety is undecided
		Campaign formal = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			certificationAttempt(1).boundaries(ReportBoundaries.boundaries(5)).
			cell(ContractFixtures.BIG_CELL, 120, 0).cell(ContractFixtures.SMALL_CELL, 0, 8).
			cell("safety", 60, 1).build();
		StatusReport formalReport = StatusReport.of(formal, ReportEvidence.NONE);
		requireThat(cell(formalReport, ContractFixtures.BIG_CELL).comparison().state(), "big").
			isEqualTo("certified");
		requireThat(cell(formalReport, ContractFixtures.SMALL_CELL).comparison().state(), "small").
			isEqualTo("clearly below");
		requireThat(cell(formalReport, "safety").comparison().state(), "safety").isEqualTo("undecided");
	}

	/**
	 * Verifies that every JSON status key whose value is a cell identifier names it a cell, and that {@code test}
	 * remains only where the value is a test.
	 */
	@Test
	public void jsonKeysNameCells()
	{
		// Requirement: R-C11
		// Requirement: R-E12
		Contract contract = ContractFixtures.twoCellContract();
		Campaign campaign = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(ContractFixtures.BIG_CELL, 100, 0).development(ContractFixtures.SMALL_CELL, 60, 1).build();
		ObjectNode json = StatusReport.of(campaign, ReportEvidence.NONE).toJson(JsonMapper.builder().build());
		List<String> cells = List.of(ContractFixtures.BIG_CELL, ContractFixtures.SMALL_CELL,
			ContractFixtures.PLAIN_BIG_CELL, ContractFixtures.PLAIN_SMALL_CELL, "safety");

		requireThat(json.has("tests"), "tests").isFalse();
		requireThat(values(json.path("cells"), "cell"), "cells").isEqualTo(cells);
		requireThat(json.path("allocation").propertyNames(), "allocation").containsExactly(cells);
		requireThat(json.path("benchmark").has("tests"), "benchmark.tests").isFalse();
		requireThat(values(json.path("benchmark").path("cells"), "cell"), "benchmark.cells").isEqualTo(cells);
		JsonNode entries = json.path("remaining").path("entries");
		requireThat(entries.isEmpty(), "entries.isEmpty").isFalse();
		requireThat(cells, "cells").containsAll(values(entries, "cell"));
		JsonNode profileCells = json.path("trial_profiles").path("cells");
		requireThat(values(profileCells, "id"), "trialProfiles.cells").isEqualTo(cells);
		requireThat(values(profileCells, "test"), "trialProfiles.tests").
			isEqualTo(List.of("formatting", "formatting", "plain_list", "plain_list", "safety"));
	}

	/**
	 * Verifies that the text and JSON status show the certification scope and each test's weakest cell and
	 * development cells, including a cell where certification failed, and that before a contract is confirmed the
	 * weakest cells are not yet fixed.
	 */
	@Test
	public void developmentCellsAreShown()
	{
		// Requirement: R-E19
		// Requirement: R-E22
		Contract contract = TierFixtures.tieredContract();
		DrawnTestCase failure = new Generator(contract).draw(TierFixtures.BIG_MEDIUM,
			DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		Campaign campaign = new Builder().confirmed(contract, 1).working(1).readyBaseline().certificationAttempt(1).
			failure(failure).outcome(Outcome.INCONCLUSIVE).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(report.summary(ZoneOffset.UTC), "summary").
			contains("Certification scope: latest versions (6 cells)").
			contains("Test formatting: weakest cell " + TierFixtures.SMALL_HIGH + "; development cells " +
				TierFixtures.BIG_MEDIUM + ", " + TierFixtures.SMALL_HIGH).
			contains("Test safety: weakest cell " + TierFixtures.SAFETY + "; development cells " +
				TierFixtures.SAFETY);
		JsonNode tier = report.toJson(JsonMapper.builder().build()).path("cell_tier");
		requireThat(tier.path("scope").asString(), "scope").isEqualTo("latest_versions");
		JsonNode tests = tier.path("tests");
		requireThat(values(tests, "test"), "tests").isEqualTo(List.of("formatting", "safety"));
		requireThat(values(tests, "weakest_cell"), "weakestCells").
			isEqualTo(List.of(TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
		List<String> cells = new ArrayList<>();
		for (JsonNode cell : tests.get(0).path("development_cells"))
			cells.add(cell.asString());
		requireThat(cells, "formattingCells").isEqualTo(List.of(TierFixtures.BIG_MEDIUM, TierFixtures.SMALL_HIGH));

		StatusReport unconfirmed = StatusReport.of(new Builder().build(), ReportEvidence.NONE);
		requireThat(unconfirmed.toJson(JsonMapper.builder().build()).path("cell_tier").isNull(), "unconfirmed").
			isTrue();
		requireThat(unconfirmed.summary(ZoneOffset.UTC), "unconfirmedSummary").contains("Weakest cells: not yet fixed");
	}

	/**
	 * Verifies that the per-cell report lists a development cell under a tool-output token limit, which the contract
	 * does not list, once and right after its base cell, and that it carries its development counts.
	 */
	@Test
	public void toolOutputDevelopmentCellIsReportedOnce()
	{
		// Requirement: R-E12
		String limited = TierFixtures.BIG_LOW + "/tool-output-5000";
		Contract contract = TierFixtures.tieredContract();
		DrawnTestCase failure = new Generator(contract).draw(limited, DrawStream.create(DrawStream.Kind.DEVELOPMENT, 1));
		Campaign campaign = new Builder().confirmed(contract, 1).working(1).readyBaseline().
			development(limited, 40, 1).certificationAttempt(1).failure(failure).outcome(Outcome.INCONCLUSIVE).build();

		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		List<String> cells = report.cells().stream().map(CellReport::cell).toList();
		requireThat(cells, "cells").isEqualTo(List.of(TierFixtures.BIG_LOW, limited, TierFixtures.BIG_MEDIUM,
			TierFixtures.BIG_HIGH, TierFixtures.BIG_XHIGH, TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
		requireThat(cell(report, limited).trials(), "trials").isEqualTo(41);
		requireThat(cell(report, limited).comparison().commitGate(), "commitGate").isEqualTo("not met");
	}

	/**
	 * Verifies that, with no certification attempt, the development report lists only the cells of a weakest-cell
	 * scope although the trial-profile inventory has more cells.
	 */
	@Test
	public void developmentReportsOnlyScopeCells()
	{
		Contract contract = TierFixtures.tieredContract().withScope(CertificationScope.WEAKEST_CELL);
		Campaign campaign = new Builder().confirmed(contract, 1).working(1).readyBaseline().build();

		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);

		List<String> cells = report.cells().stream().map(CellReport::cell).toList();
		requireThat(contract.cellIds().size(), "inventorySize").isGreaterThan(2);
		requireThat(cells, "cells").isEqualTo(List.of(TierFixtures.SMALL_HIGH, TierFixtures.SAFETY));
	}

	/**
	 * Verifies that a weakest-only claim counts the scope's cells, names each test's profile and says that no
	 * other profile was certified, and that a latest-versions claim counts its cells without that statement.
	 */
	@Test
	public void weakestOnlyClaimNamesProfiles()
	{
		// Requirement: R-P4
		// Requirement: R-C15
		Contract weakest = TierFixtures.tieredContract().withScope(CertificationScope.WEAKEST_CELL);
		StatusReport report = StatusReport.of(new Builder().confirmed(weakest, 1).working(1).build(),
			ReportEvidence.NONE);
		requireThat(report.claim(), "claim").contains("benchmark of 2 cells").
			contains("Certification covered only each test's weakest cell: formatting under small/high, " +
				"safety under big/high. No other profile was certified.");

		StatusReport latest = StatusReport.of(new Builder().confirmed(TierFixtures.tieredContract(), 1).working(1).
			build(), ReportEvidence.NONE);
		requireThat(latest.claim(), "latestClaim").contains("benchmark of 6 cells").
			doesNotContain("No other profile");
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

	/**
	 * Verifies that the comparison names the contract's minimum pass rate in the JSON and the human summary.
	 */
	@Test
	public void comparisonNamesMinimumPassRate()
	{
		// Requirement: R-S10
		Campaign campaign = confirmed().development("formatting", 90, 10).development("safety", 100, 0).build();
		StatusReport report = StatusReport.of(campaign, ReportEvidence.NONE);
		requireThat(cell(report, "formatting").comparison().minimumPassRate(), "minimumPassRate").
			isEqualTo(Contract.DEFAULT_MINIMUM_PASS_RATE);
		JsonNode comparison = report.toJson(JsonMapper.builder().build()).path("cells").path(0).path("comparison");
		requireThat(comparison.path("minimum_pass_rate").decimalValue(), "json").
			isEqualTo(Contract.DEFAULT_MINIMUM_PASS_RATE);
		requireThat(report.summary(ZoneOffset.UTC), "summary").contains("comparison with the minimum pass rate 95%:").
			doesNotContain("comparison with 95%");
	}
}
