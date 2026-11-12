package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

/**
 * How a candidate development judge's verdicts on a trial compared with the frozen judge's, whose verdict counted.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param candidate the candidate that judged the trial next to the frozen judge
 * @param spotCheck {@code true} if the frozen judge also judged the trial as a spot check of a qualified candidate
 *                  rather than as part of the candidate's shadowing
 * @param heldOut   {@code true} if the trial is not a development trial, such as a baseline or certification trial,
 *                  which the frozen judge judges anyway and the candidate shadowed
 * @param lenient   {@code true} if the candidate passed an assertion that the frozen judge failed
 * @param strict    {@code true} if the candidate failed an assertion that the frozen judge passed
 */
public record ShadowResult(TrialProfile candidate, boolean spotCheck, boolean heldOut, boolean lenient,
	boolean strict)
{
	/**
	 * Creates a comparison.
	 *
	 * @param candidate the candidate that judged the trial
	 * @param spotCheck {@code true} if the trial is a spot check
	 * @param heldOut   {@code true} if the trial is not a development trial
	 * @param lenient   {@code true} if the candidate passed an assertion that the frozen judge failed
	 * @param strict    {@code true} if the candidate failed an assertion that the frozen judge passed
	 * @throws NullPointerException     if {@code candidate} is null
	 * @throws IllegalArgumentException if a held-out trial is a spot check
	 */
	public ShadowResult
	{
		requireThat(candidate, "candidate").isNotNull();
		if (spotCheck)
			requireThat(heldOut, "heldOut").isFalse();
	}

	/**
	 * Creates a comparison on a development trial.
	 *
	 * @param candidate the candidate that judged the trial
	 * @param spotCheck {@code true} if the trial is a spot check
	 * @param lenient   {@code true} if the candidate passed an assertion that the frozen judge failed
	 * @param strict    {@code true} if the candidate failed an assertion that the frozen judge passed
	 * @throws NullPointerException if {@code candidate} is null
	 */
	public ShadowResult(TrialProfile candidate, boolean spotCheck, boolean lenient, boolean strict)
	{
		this(candidate, spotCheck, false, lenient, strict);
	}
}
