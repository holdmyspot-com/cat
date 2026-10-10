package io.github.cowwoc.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.cat.common.optimizer.campaign.test.Builder.counts;
import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.campaign.Campaign;
import io.github.cowwoc.cat.common.optimizer.campaign.CampaignEvent;
import io.github.cowwoc.cat.common.optimizer.campaign.CellCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.ControlPairing;
import io.github.cowwoc.cat.common.optimizer.campaign.ControlPairing.UnpairedTrial;
import io.github.cowwoc.cat.common.optimizer.campaign.DecisionTable;
import io.github.cowwoc.cat.common.optimizer.campaign.PairCounts;
import io.github.cowwoc.cat.common.optimizer.campaign.Purpose;
import io.github.cowwoc.cat.common.optimizer.campaign.Transitions;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialAdoption;
import io.github.cowwoc.cat.common.optimizer.campaign.TrialKey;
import io.github.cowwoc.cat.common.optimizer.campaign.Verdict;
import io.github.cowwoc.cat.common.optimizer.contract.TestCase;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.testng.annotations.Test;

/**
 * Tests {@link TrialAdoption}: representative outcomes enter counts in assignment order, and outcomes received
 * after a decision do not change it.
 */
public final class AdoptionTest
{
	/**
	 * Creates the test.
	 */
	public AdoptionTest()
	{
	}

	/**
	 * Verifies that a development outcome waits for an earlier-assigned outcome of the same cell, purpose, series
	 * and snapshot.
	 */
	@Test
	public void laterOutcomeWaitsForEarlier()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey first = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey second = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(first));
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(second));

		campaign = TransitionSteps.complete(campaign, second, Verdict.PASS);
		requireThat(campaign.development().count("safety"), "afterSecond").isEqualTo(counts(10, 0));
		requireThat(campaign.trials().size(), "unadopted").isEqualTo(2);

		campaign = TransitionSteps.complete(campaign, first, Verdict.FAIL);
		requireThat(campaign.development().count("safety"), "afterFirst").isEqualTo(counts(11, 1));
		requireThat(campaign.development().count("safety").batchFailures(), "batchFailures").
			isEqualTo(List.of(1));
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that two cells of the same purpose and snapshot number their trials separately from 1, and adoption
	 * follows each cell's numbering even when assignments to the two cells are interleaved.
	 */
	@Test
	public void eachGroupNumbersFromOne()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey firstSafety = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "formatting");
		TrialKey firstFormatting = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey secondSafety = TransitionSteps.last(campaign);
		requireThat(firstSafety.sequence(), "firstSafety").isEqualTo(1L);
		requireThat(firstFormatting.sequence(), "firstFormatting").isEqualTo(1L);
		requireThat(secondSafety.sequence(), "secondSafety").isEqualTo(2L);

		campaign = TransitionSteps.run(campaign, secondSafety, Verdict.FAIL);
		campaign = TransitionSteps.run(campaign, firstFormatting, Verdict.PASS);
		requireThat(campaign.development().count("formatting"), "formatting").isEqualTo(counts(1, 0));
		requireThat(campaign.development().count("safety"), "waiting").isEqualTo(CellCounts.EMPTY);

		campaign = TransitionSteps.run(campaign, firstSafety, Verdict.PASS);
		requireThat(campaign.development().count("safety"), "safety").isEqualTo(counts(1, 1));
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that a development outcome in one cell does not wait for a running development trial in another cell.
	 */
	@Test
	public void otherCellDoesNotWait()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey safety = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(safety));
		campaign = TransitionSteps.adopt(campaign, Purpose.DEVELOPMENT, "formatting", Verdict.PASS);
		requireThat(campaign.development().count("formatting"), "formatting").isEqualTo(counts(1, 0));
		requireThat(campaign.development().count("safety"), "safety").isEqualTo(CellCounts.EMPTY);
	}

	/**
	 * Verifies that a cell's control trial judged before its earlier-assigned deciding control trial is discarded once
	 * that trial certifies the cell.
	 */
	@Test
	public void postDecisionOutcomeIsIgnored()
	{
		// Requirement: R-E5
		Set<String> all = Set.of("uses_requested_tone", TestCase.EXPECTED_OUTCOME);
		PairCounts candidateAhead = new PairCounts(0, 6, 0, 0);
		Map<String, PairCounts> comparisons = new LinkedHashMap<>();
		comparisons.put("uses_requested_tone", candidateAhead);
		comparisons.put(TestCase.EXPECTED_OUTCOME, candidateAhead);
		ControlPairing pairing = new ControlPairing(List.of(
			new UnpairedTrial(Builder.testCase("formatting", "formal"), all),
			new UnpairedTrial(Builder.testCase("formatting", "casual"), all)), comparisons);
		Campaign campaign = new Builder().confirmed().working(1).certificationAttempt(1).
			cell("formatting", 100, 0).pairing("formatting", pairing).cell("safety", 10, 0).build();
		campaign = TransitionSteps.assignControl(campaign, "formatting");
		TrialKey deciding = TransitionSteps.last(campaign);
		campaign = TransitionSteps.assignControl(campaign, "formatting");
		TrialKey late = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(deciding));
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(late));
		campaign = TransitionSteps.complete(campaign, late, Verdict.PASS);
		campaign = TransitionSteps.complete(campaign, deciding, Verdict.FAIL);

		ControlPairing adopted = campaign.attempt(1).pairing("formatting");
		requireThat(DecisionTable.cellDecision(campaign.attempt(1), "formatting"), "decision").
			isEqualTo(DecisionTable.CellDecision.CERTIFIED);
		requireThat(adopted.comparisons().get("uses_requested_tone"), "pairs").isEqualTo(new PairCounts(0, 7, 0, 0));
		requireThat(adopted.unpaired().size(), "unpaired").isEqualTo(1);
		requireThat(campaign.trials(), "trials").isEmpty();
	}

	/**
	 * Verifies that a trial whose candidate was replaced is dropped, while the replacement's counts start at zero.
	 */
	@Test
	public void replacedCandidateOutcomeIsDropped()
	{
		// Requirement: R-E5
		Campaign campaign = new Builder().confirmed().working(1).development("safety", 10, 0).build();
		campaign = TransitionSteps.assign(campaign, Purpose.DEVELOPMENT, "safety");
		TrialKey old = TransitionSteps.last(campaign);
		campaign = Transitions.apply(campaign, new CampaignEvent.Start(old));
		Campaign replaced = new Builder().confirmed().working(2).series(2).trial(campaign.trials().getFirst()).
			build();
		replaced = TransitionSteps.complete(replaced, old, Verdict.FAIL);
		requireThat(replaced.development().count("safety"), "counts").isEqualTo(CellCounts.EMPTY);
		requireThat(replaced.trials(), "trials").isEmpty();
	}
}
