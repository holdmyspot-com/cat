package com.holdmyspot.cat.common.optimizer.campaign.test;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge;
import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge.Stage;
import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge;
import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge.Rejection;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Tests how a prompt test chooses the judge of its development trials from its candidates.
 */
public final class DevelopmentJudgeTest
{
	private static final TrialProfile CHEAP = new TrialProfile("cheap-model", "low");
	private static final TrialProfile MIDDLE = new TrialProfile("middle-model", "medium");
	private static final Map<String, Boolean> LABELS = Map.of("answer/satisfying/1", true, "answer/failing/1", false);

	/**
	 * Creates the test.
	 */
	public DevelopmentJudgeTest()
	{
	}

	/**
	 * Returns a judge choice whose cheapest candidate shadows the frozen judge.
	 *
	 * @param candidates the candidates, cheapest first
	 * @return the choice
	 */
	private static DevelopmentJudge shadowing(List<TrialProfile> candidates)
	{
		return DevelopmentJudge.start(LABELS, candidates).nearMissesChecked(candidates.getFirst(), true);
	}

	/**
	 * Verifies that a test without a candidate keeps the frozen judge.
	 */
	@Test
	public void noCandidateKeepsFrozenJudge()
	{
		DevelopmentJudge judge = DevelopmentJudge.start(LABELS, List.of());

		requireThat(judge.stage(), "stage").isEqualTo(Stage.FROZEN);
		requireThat(judge.current().isEmpty(), "current().isEmpty()").isTrue();
	}

	/**
	 * Verifies that the cheapest candidate is examined first, and starts with the near misses.
	 */
	@Test
	public void cheapestCandidateIsExaminedFirst()
	{
		DevelopmentJudge judge = DevelopmentJudge.start(LABELS, List.of(CHEAP, MIDDLE));

		requireThat(judge.stage(), "stage").isEqualTo(Stage.NEAR_MISSES);
		requireThat(judge.current().orElseThrow(), "current").isEqualTo(CHEAP);
	}

	/**
	 * Verifies that a candidate that labels a near miss differently is rejected without shadowing a trial, and the next
	 * candidate is examined.
	 */
	@Test
	public void nearMissDisagreementRejectsCandidate()
	{
		DevelopmentJudge judge = DevelopmentJudge.start(LABELS, List.of(CHEAP, MIDDLE)).
			nearMissesChecked(CHEAP, false);

		requireThat(judge.stage(), "stage").isEqualTo(Stage.NEAR_MISSES);
		requireThat(judge.current().orElseThrow(), "current").isEqualTo(MIDDLE);
		requireThat(judge.rejected(), "rejected").
			isEqualTo(List.of(new RejectedJudge(CHEAP, Rejection.NEAR_MISS, 0, 0, 0, 0)));
		requireThat(judge.shadowed(), "shadowed").isEqualTo(0);
	}

	/**
	 * Verifies that the test returns to the frozen judge once its last candidate fails a near miss.
	 */
	@Test
	public void lastCandidateRejectedKeepsFrozenJudge()
	{
		DevelopmentJudge judge = DevelopmentJudge.start(LABELS, List.of(CHEAP)).nearMissesChecked(CHEAP, false);

		requireThat(judge.stage(), "stage").isEqualTo(Stage.FROZEN);
		requireThat(judge.current().isEmpty(), "current().isEmpty()").isTrue();
	}

	/**
	 * Verifies that a candidate that agrees on every near miss starts shadowing, and that a verdict about a candidate
	 * that is not in examination changes nothing.
	 */
	@Test
	public void agreeingCandidateStartsShadowing()
	{
		DevelopmentJudge judge = DevelopmentJudge.start(LABELS, List.of(CHEAP, MIDDLE)).
			nearMissesChecked(MIDDLE, false).nearMissesChecked(CHEAP, true);

		requireThat(judge.stage(), "stage").isEqualTo(Stage.SHADOWING);
		requireThat(judge.current().orElseThrow(), "current").isEqualTo(CHEAP);
		requireThat(judge.shadowed(), "shadowed").isEqualTo(0);
	}

	/**
	 * Verifies that 298 agreeing shadowed trials leave the candidate shadowing and the 299th qualifies it.
	 */
	@Test
	public void agreeingTrialsQualifyAt299()
	{
		DevelopmentJudge judge = shadowing(List.of(CHEAP));
		for (int trial = 1; trial <= 298; ++trial)
			judge = judge.trialShadowed(CHEAP, false, false, false);

		requireThat(judge.stage(), "stage_after_298").isEqualTo(Stage.SHADOWING);
		requireThat(judge.shadowed(), "shadowed_after_298").isEqualTo(298);

		judge = judge.trialShadowed(CHEAP, false, false, false);

		requireThat(judge.stage(), "stage_after_299").isEqualTo(Stage.QUALIFIED);
		requireThat(judge.shadowed(), "shadowed_after_299").isEqualTo(299);
		requireThat(judge.current().orElseThrow(), "current").isEqualTo(CHEAP);
	}

	/**
	 * Verifies that a lenient disagreement, whose bound cannot be within 1% in 400 trials, rejects the candidate with
	 * the counts it had, and that the next candidate starts over with the near misses.
	 */
	@Test
	public void lenientDisagreementRejectsCandidate()
	{
		DevelopmentJudge judge = shadowing(List.of(CHEAP, MIDDLE));
		for (int trial = 1; trial <= 10; ++trial)
			judge = judge.trialShadowed(CHEAP, false, false, false);
		judge = judge.trialShadowed(CHEAP, false, true, false);

		requireThat(judge.stage(), "stage").isEqualTo(Stage.NEAR_MISSES);
		requireThat(judge.current().orElseThrow(), "current").isEqualTo(MIDDLE);
		requireThat(judge.rejected(), "rejected").
			isEqualTo(List.of(new RejectedJudge(CHEAP, Rejection.SHADOWING, 11, 0, 1, 0)));
		requireThat(judge.shadowed(), "shadowed").isEqualTo(0);
		requireThat(judge.lenient(), "lenient").isEqualTo(0);
	}

	/**
	 * Verifies that strict disagreements are tolerated up to the number that 400 trials can still bound within 5%, and
	 * that one more rejects the candidate.
	 */
	@Test
	public void strictDisagreementsAreBounded()
	{
		DevelopmentJudge judge = shadowing(List.of(CHEAP));
		for (int trial = 1; trial <= 12; ++trial)
			judge = judge.trialShadowed(CHEAP, false, false, true);

		requireThat(judge.stage(), "stage_after_12").isEqualTo(Stage.SHADOWING);
		requireThat(judge.strict(), "strict_after_12").isEqualTo(12);

		judge = judge.trialShadowed(CHEAP, false, false, true);

		requireThat(judge.stage(), "stage_after_13").isEqualTo(Stage.FROZEN);
		requireThat(judge.rejected().getFirst().reason(), "reason").isEqualTo(Rejection.SHADOWING);
		requireThat(judge.rejected().getFirst().strict(), "strict").isEqualTo(13);
	}

	/**
	 * Verifies that a disagreement that counts as both lenient and strict counts the trial once in each kind.
	 */
	@Test
	public void trialCountsOncePerKind()
	{
		DevelopmentJudge judge = shadowing(List.of(CHEAP)).trialShadowed(CHEAP, false, false, true).
			trialShadowed(CHEAP, false, false, false);

		requireThat(judge.shadowed(), "shadowed").isEqualTo(2);
		requireThat(judge.strict(), "strict").isEqualTo(1);
		requireThat(judge.lenient(), "lenient").isEqualTo(0);
	}

	/**
	 * Verifies that no candidate is left once the last one is rejected while shadowing, and that later results change
	 * nothing.
	 */
	@Test
	public void noCandidateLeftKeepsFrozenJudge()
	{
		DevelopmentJudge judge = shadowing(List.of(CHEAP)).trialShadowed(CHEAP, false, true, false);

		requireThat(judge.stage(), "stage").isEqualTo(Stage.FROZEN);
		requireThat(judge.current().isEmpty(), "current().isEmpty()").isTrue();
		requireThat(judge.trialShadowed(CHEAP, false, false, false), "later_trial").isEqualTo(judge);
		requireThat(judge.trialShadowed(CHEAP, true, false, false), "later_spot_check").isEqualTo(judge);
	}

	/**
	 * Verifies that spot checks that agree keep a qualified candidate, and are counted.
	 */
	@Test
	public void agreeingSpotChecksKeepCandidate()
	{
		DevelopmentJudge judge = shadowing(List.of(CHEAP));
		for (int trial = 1; trial <= 299; ++trial)
			judge = judge.trialShadowed(CHEAP, false, false, false);
		for (int check = 1; check <= 30; ++check)
			judge = judge.trialShadowed(CHEAP, true, false, false);

		requireThat(judge.stage(), "stage").isEqualTo(Stage.QUALIFIED);
		requireThat(judge.spotChecked(), "spotChecked").isEqualTo(30);
		requireThat(judge.shadowed(), "shadowed").isEqualTo(299);
	}

	/**
	 * Verifies that a spot check that finds a lenient disagreement returns the test to the frozen judge for good, with
	 * the candidate recorded as rejected by the spot check, even though another candidate remains.
	 */
	@Test
	public void spotCheckRevertsQualifiedCandidate()
	{
		DevelopmentJudge judge = shadowing(List.of(CHEAP, MIDDLE));
		for (int trial = 1; trial <= 299; ++trial)
			judge = judge.trialShadowed(CHEAP, false, false, false);
		judge = judge.trialShadowed(CHEAP, true, true, false);

		requireThat(judge.stage(), "stage").isEqualTo(Stage.FROZEN);
		requireThat(judge.current().isEmpty(), "current().isEmpty()").isTrue();
		requireThat(judge.rejected(), "rejected").
			isEqualTo(List.of(new RejectedJudge(CHEAP, Rejection.SPOT_CHECK, 299, 1, 1, 0)));
		requireThat(judge.candidates().isEmpty(), "candidates().isEmpty()").isTrue();
	}

	/**
	 * Verifies that a result that belongs to another stage or candidate than the one in examination changes nothing:
	 * a spot check while shadowing, a shadowed trial after qualification, and a trial of another candidate.
	 */
	@Test
	public void staleResultsAreIgnored()
	{
		DevelopmentJudge shadowing = shadowing(List.of(CHEAP, MIDDLE));

		requireThat(shadowing.trialShadowed(CHEAP, true, true, true), "spot_check_while_shadowing").
			isEqualTo(shadowing);
		requireThat(shadowing.trialShadowed(MIDDLE, false, true, true), "other_candidate").isEqualTo(shadowing);

		DevelopmentJudge qualified = shadowing;
		for (int trial = 1; trial <= 299; ++trial)
			qualified = qualified.trialShadowed(CHEAP, false, false, false);

		requireThat(qualified.trialShadowed(CHEAP, false, true, true), "shadowed_trial_after_qualification").
			isEqualTo(qualified);
	}
}
