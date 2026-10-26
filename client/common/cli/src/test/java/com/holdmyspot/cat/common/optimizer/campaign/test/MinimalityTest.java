package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.holdmyspot.cat.common.optimizer.campaign.PromptAuthorAttempt;
import com.holdmyspot.cat.common.optimizer.campaign.Campaign;
import com.holdmyspot.cat.common.optimizer.campaign.CampaignEvent;
import com.holdmyspot.cat.common.optimizer.campaign.TestCaseOutcomes;
import com.holdmyspot.cat.common.optimizer.campaign.Minimality;
import com.holdmyspot.cat.common.optimizer.campaign.Promotion;
import com.holdmyspot.cat.common.optimizer.campaign.PromotionRun;
import com.holdmyspot.cat.common.optimizer.campaign.Purpose;
import com.holdmyspot.cat.common.optimizer.campaign.Round;
import com.holdmyspot.cat.common.optimizer.campaign.Transitions;
import com.holdmyspot.cat.common.optimizer.campaign.TrialKey;
import com.holdmyspot.cat.common.optimizer.campaign.Verdict;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.inventory.FileContent;
import com.holdmyspot.cat.common.optimizer.inventory.FileDelta;
import com.holdmyspot.cat.common.optimizer.inventory.FileEntry.RegularFile;
import com.holdmyspot.cat.common.optimizer.inventory.FileInventory;
import com.holdmyspot.cat.common.optimizer.inventory.Hunks;
import com.holdmyspot.cat.common.optimizer.inventory.Overlay;
import com.holdmyspot.cat.common.optimizer.inventory.OverlayEntry.Write;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Verifies that a passing attempt enters minimality before it becomes a candidate.
 */
public final class MinimalityTest
{
	/**
	 * Creates the test.
	 */
	public MinimalityTest()
	{
	}

	/**
	 * A reduction needs the same extended passing streak that established the accepted fix.
	 */
	// Requirement: R-D11
	@Test
	public void reductionUsesExtendedStreak()
	{
		Sha256 original = Builder.snapshot("original");
		Sha256 reduced = Builder.snapshot("reduced");
		Campaign campaign = TransitionSteps.submit(PromotionTest.streakRound(1), "safety", original, List.of(
			new FileDelta(Path.of("CLAUDE.md"), new FileContent("before".getBytes(UTF_8)),
				new FileContent("after".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"))));
		DrawnTestCase testCase = campaign.development().promptAuthor().runs().getFirst().testCase();
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, original, testCase, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, original, testCase, Verdict.PASS);
		requireThat(campaign.development().promptAuthor().runs(), "originalExtension").size().isEqualTo(3);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, original, testCase, Verdict.PASS);
		campaign = Transitions.apply(campaign, new CampaignEvent.AdvanceMinimality(reduced));
		requireThat(campaign.development().promptAuthor().runs(), "reductionStartingRuns").size().isEqualTo(2);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, reduced, testCase, Verdict.PASS);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, reduced, testCase, Verdict.PASS);
		PromptAuthorAttempt promptAuthor = campaign.development().promptAuthor();
		requireThat(promptAuthor.runs(), "reductionExtension").size().isEqualTo(3);
		requireThat(Promotion.decide(promptAuthor, campaign.development().working().snapshot(),
			campaign.lineage().regressionSet(),
			campaign.development().testCaseOutcomes()), "pendingReduction").
			isEqualTo(Promotion.Decision.PENDING);
		campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, reduced, testCase, Verdict.PASS);
		requireThat(Promotion.decide(campaign.development().promptAuthor(), campaign.development().working().snapshot(),
			campaign.lineage().regressionSet(),
				campaign.development().testCaseOutcomes()),
			"acceptedReduction").isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Verifies that passing promotion trials preserve the old working candidate until reduction finishes.
	 */
	// Requirement: R-D11
	@Test
	public void passingAttemptWaitsForReduction()
	{
		// Requirement: R-D11
		DrawnTestCase failure = Builder.targeted("safety", "cache");
		Sha256 snapshot = Builder.snapshot("attempt");
		Campaign campaign = new Builder().confirmed().working(1).failure(failure).
			round(new Round(Map.of("safety", 0))).build();
		campaign = TransitionSteps.submit(campaign, "safety", snapshot, List.of(
			new FileDelta(Path.of("CLAUDE.md"), new FileContent("before".getBytes(UTF_8)),
				new FileContent("after".getBytes(UTF_8)), PosixFilePermissions.fromString("rw-r--r--"))));
		for (int run = 0; run < Promotion.STARTING_TRIGGERING_RUNS; ++run)
			campaign = TransitionSteps.adopt(campaign, Purpose.PROMOTION, 1, snapshot, failure, Verdict.PASS);
		requireThat(campaign.development().working().number(), "working").isEqualTo(1);
		requireThat(campaign.development().promptAuthor(), "attempt").isNotNull();
		requireThat(campaign.attempts(), "allowance").isEmpty();
	}

	/**
	 * A reduction must keep every accepted fix even when a different test case is still fixed.
	 */
	// Requirement: R-D11
	@Test
	public void reductionKeepsEveryAcceptedFix()
	{
		Sha256 snapshot = Builder.snapshot("attempt");
		DrawnTestCase logs = Builder.targeted("safety", "logs");
		DrawnTestCase cache = Builder.targeted("safety", "cache");
		List<PromotionRun> accepted = List.of(run(logs, Verdict.PASS), run(logs, Verdict.PASS),
			run(cache, Verdict.PASS), run(cache, Verdict.PASS));
		Minimality search = new Minimality(List.of(), List.of("CLAUDE.md#1", "CLAUDE.md#2"), 2, 0,
			List.of("CLAUDE.md#2"), snapshot, accepted);
		PromptAuthorAttempt lost = new PromptAuthorAttempt(1, "safety", snapshot, List.of(run(logs, Verdict.FAIL),
			run(logs, Verdict.PASS), run(cache, Verdict.PASS), run(cache, Verdict.PASS)), search);
		PromptAuthorAttempt kept = new PromptAuthorAttempt(1, "safety", snapshot, accepted, search);
		requireThat(Promotion.decide(lost, Builder.snapshot("working"), List.of(), List.of(
			new TestCaseOutcomes(logs, 0, 4), new TestCaseOutcomes(cache, 0, 4))), "lostFix").
			isEqualTo(Promotion.Decision.REJECT);
		requireThat(Promotion.decide(kept, Builder.snapshot("working"), List.of(), List.of(
			new TestCaseOutcomes(logs, 0, 4), new TestCaseOutcomes(cache, 0, 4))), "keptFixes").
			isEqualTo(Promotion.Decision.PROMOTE);
	}

	/**
	 * Returns a triggering outcome for reduction evidence.
	 *
	 * @param testCase the triggering test case
	 * @param verdict its outcome
	 * @return the run
	 */
	private static PromotionRun run(DrawnTestCase testCase, Verdict verdict)
	{
		return new PromotionRun(PromotionRun.Role.TRIGGERING, testCase,
			new TrialKey(Purpose.PROMOTION, 1, Builder.snapshot("attempt"), testCase.cellId(), 1), verdict);
	}

	/**
	 * Verifies that failed coarse removals refine to single hunks and preserve the two necessary changes.
	 */
	// Requirement: R-D11
	@Test
	public void refinesUntilNoHunkCanBeRemoved()
	{
		FileDelta file = new FileDelta(Path.of("CLAUDE.md"),
			new FileContent("a\nx\nb\ny\nc\nz\nd\n".getBytes(UTF_8)),
			new FileContent("needed-a\nx\nunneeded-b\ny\nneeded-c\nz\nunneeded-d\n".getBytes(UTF_8)),
			PosixFilePermissions.fromString("rw-r--r--"));
		Overlay working = Overlay.of(new FileInventory(Path.of("/project").toAbsolutePath(), Map.of(file.path(),
			new RegularFile(file.before().hash(), file.permissions()))));
		DrawnTestCase first = Builder.targeted("safety", "logs");
		DrawnTestCase second = Builder.targeted("safety", "cache");
		List<PromotionRun> accepted = List.of(run(first, Verdict.PASS), run(first, Verdict.PASS),
			run(second, Verdict.PASS), run(second, Verdict.PASS));
		Minimality search = Minimality.start(List.of(file), Builder.snapshot("full")).
			advance(Builder.snapshot("full"), accepted, Promotion.Decision.PROMOTE);
		for (int iteration = 0; iteration < 20 && search.testing() != null; ++iteration)
		{
			Overlay reduced = Hunks.apply(working, search.files(), Set.copyOf(search.testing()));
			FileContent content = file.before();
			if (reduced.entries().get(file.path()) instanceof Write write)
				content = write.content();
			List<String> lines = new String(content.bytes(), UTF_8).lines().toList();
			Verdict firstVerdict = Verdict.FAIL;
			if (lines.contains("needed-a"))
				firstVerdict = Verdict.PASS;
			Verdict secondVerdict = Verdict.FAIL;
			if (lines.contains("needed-c"))
				secondVerdict = Verdict.PASS;
			List<PromotionRun> runs = List.of(run(first, firstVerdict), run(first, firstVerdict),
				run(second, secondVerdict), run(second, secondVerdict));
			PromptAuthorAttempt attempt = new PromptAuthorAttempt(1, "safety", reduced.identity(), runs, search);
			search = search.advance(reduced.identity(), runs, Promotion.decide(attempt, working.identity(), List.of(),
				List.of(new TestCaseOutcomes(first, 0, 4), new TestCaseOutcomes(second, 0, 4))));
		}
		requireThat(search.testing(), "finished").isNull();
		requireThat(search.removed(), "removed").isEqualTo(List.of("CLAUDE.md#2", "CLAUDE.md#4"));
		Write retained = (Write) Hunks.apply(working, search.files(), Set.copyOf(search.retained())).entries().
			get(file.path());
		requireThat(new String(retained.content().bytes(), UTF_8), "minimalPrompt").
			isEqualTo("needed-a\nx\nb\ny\nneeded-c\nz\nd\n");
	}
}
