package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.DevelopmentJudge.Stage;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

/**
 * A candidate development judge that a prompt test has started to examine, with the trials that compared it with the
 * frozen judge.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param profile     the candidate's profile
 * @param stage       how far the candidate has come: {@link Stage#NEAR_MISSES}, {@link Stage#SHADOWING} or
 *                    {@link Stage#QUALIFIED}
 * @param shadowed    the number of trials that the frozen judge and the candidate both judged during shadowing,
 *                    development and held-out trials together
 * @param heldOut     the number of those shadowed trials that were not development trials
 * @param spotChecked the number of development trials that the frozen judge also judged after the candidate qualified
 * @param lenient     the number of compared trials in which the candidate passed an assertion that the frozen judge
 *                    failed
 * @param strict      the number of compared trials in which the candidate failed an assertion that the frozen judge
 *                    passed
 */
public record ExaminedJudge(TrialProfile profile, Stage stage, int shadowed, int heldOut, int spotChecked,
	int lenient, int strict)
{
	/**
	 * Creates an examined candidate.
	 *
	 * @param profile     the candidate's profile
	 * @param stage       how far the candidate has come
	 * @param shadowed    the number of shadowed trials
	 * @param heldOut     the number of shadowed trials that were not development trials
	 * @param spotChecked the number of spot-checked trials
	 * @param lenient     the number of trials with a lenient disagreement
	 * @param strict      the number of trials with a strict disagreement
	 * @throws NullPointerException     if {@code profile} or {@code stage} are null
	 * @throws IllegalArgumentException if {@code stage} is {@link Stage#FROZEN}, a count is negative,
	 *                                  {@code heldOut} exceeds {@code shadowed}, or a disagreement count exceeds the
	 *                                  trials compared
	 */
	public ExaminedJudge
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(stage, "stage").isNotNull().isNotEqualTo(Stage.FROZEN);
		requireThat(shadowed, "shadowed").isNotNegative();
		requireThat(heldOut, "heldOut").isBetween(0, true, shadowed, true);
		requireThat(spotChecked, "spotChecked").isNotNegative();
		requireThat(lenient, "lenient").isBetween(0, true, shadowed + spotChecked, true);
		requireThat(strict, "strict").isBetween(0, true, shadowed + spotChecked, true);
	}

	/**
	 * Returns a candidate that has not judged the near misses yet.
	 *
	 * @param profile the candidate's profile
	 * @return the candidate
	 * @throws NullPointerException if {@code profile} is null
	 */
	public static ExaminedJudge started(TrialProfile profile)
	{
		return new ExaminedJudge(profile, Stage.NEAR_MISSES, 0, 0, 0, 0, 0);
	}

	/**
	 * Returns the number of trials that compared the candidate with the frozen judge.
	 *
	 * @return the shadowed and spot-checked trials
	 */
	public int compared()
	{
		return shadowed + spotChecked;
	}

	/**
	 * Returns the candidate at another stage with the same counts.
	 *
	 * @param next the stage
	 * @return the candidate
	 */
	ExaminedJudge at(Stage next)
	{
		return new ExaminedJudge(profile, next, shadowed, heldOut, spotChecked, lenient, strict);
	}

	/**
	 * Returns the candidate after one more compared trial.
	 *
	 * @param shadow the comparison
	 * @return the candidate with the trial counted
	 */
	ExaminedJudge plus(ShadowResult shadow)
	{
		int newShadowed = shadowed;
		int newHeldOut = heldOut;
		int newSpotChecked = spotChecked;
		if (shadow.spotCheck())
			++newSpotChecked;
		else
		{
			++newShadowed;
			if (shadow.heldOut())
				++newHeldOut;
		}
		int newLenient = lenient;
		if (shadow.lenient())
			++newLenient;
		int newStrict = strict;
		if (shadow.strict())
			++newStrict;
		return new ExaminedJudge(profile, stage, newShadowed, newHeldOut, newSpotChecked, newLenient, newStrict);
	}

	/**
	 * Returns the record of the candidate once it is turned down.
	 *
	 * @param reason why it is turned down
	 * @return the record
	 */
	RejectedJudge rejected(RejectedJudge.Rejection reason)
	{
		return new RejectedJudge(profile, reason, shadowed, spotChecked, lenient, strict);
	}
}
