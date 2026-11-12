package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

/**
 * A candidate development judge that a prompt test turned down, with the counts it had when it was turned down.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param profile     the candidate's profile
 * @param reason      why the candidate was turned down
 * @param shadowed    the number of trials that the frozen judge and the candidate both judged during shadowing
 * @param spotChecked the number of trials that the frozen judge also judged after the candidate qualified
 * @param lenient     the number of those trials in which the candidate passed an assertion that the frozen judge failed
 * @param strict      the number of those trials in which the candidate failed an assertion that the frozen judge passed
 */
public record RejectedJudge(TrialProfile profile, Rejection reason, int shadowed, int spotChecked, int lenient,
	int strict)
{
	/**
	 * Why a candidate was turned down.
	 */
	public enum Rejection
	{
		/**
		 * The candidate judged a near miss differently from the frozen judge.
		 */
		NEAR_MISS,
		/**
		 * A bound on the candidate's disagreement rate could not reach its limit within the shadowed-trial limit.
		 */
		SHADOWING,
		/**
		 * A spot check pushed a bound on a qualified candidate's disagreement rate above its limit.
		 */
		SPOT_CHECK
	}

	/**
	 * Creates a rejected candidate.
	 *
	 * @param profile     the candidate's profile
	 * @param reason      why the candidate was turned down
	 * @param shadowed    the number of shadowed trials
	 * @param spotChecked the number of spot-checked trials
	 * @param lenient     the number of trials with a lenient disagreement
	 * @param strict      the number of trials with a strict disagreement
	 * @throws NullPointerException     if {@code profile} or {@code reason} are null
	 * @throws IllegalArgumentException if a count is negative
	 */
	public RejectedJudge
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(reason, "reason").isNotNull();
		requireThat(shadowed, "shadowed").isNotNegative();
		requireThat(spotChecked, "spotChecked").isNotNegative();
		requireThat(lenient, "lenient").isNotNegative();
		requireThat(strict, "strict").isNotNegative();
	}
}
