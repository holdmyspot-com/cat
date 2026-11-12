package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.util.List;
import java.util.Map;

/**
 * A development judge that had qualified for a prompt test when an earlier campaign on the same target ended, with
 * what its qualification rests on. A later campaign starts the test with this judge qualified only while every
 * recorded value is unchanged.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param profile      the qualified candidate's profile
 * @param shadowed     the number of trials that the frozen judge and the candidate both judged during shadowing
 * @param spotChecked  the number of trials that the frozen judge also judged after the candidate qualified
 * @param lenient      the number of compared trials in which the candidate passed an assertion that the frozen judge
 *                     failed
 * @param strict       the number of compared trials in which the candidate failed an assertion that the frozen judge
 *                     passed
 * @param testVersion  the identity of the prompt test's definition in the contract, which covers its near misses and
 *                     the frozen judge's instructions
 * @param labels       the frozen judge's label of each near-miss reply: {@code true} if it passed the reply
 * @param judgeVersion the version of CAT's judge that the frozen judge ran under
 */
public record CarriedJudge(TrialProfile profile, int shadowed, int spotChecked, int lenient, int strict,
	Sha256 testVersion, Map<String, Boolean> labels, int judgeVersion)
{
	/**
	 * Creates a carried qualification.
	 *
	 * @param profile      the qualified candidate's profile
	 * @param shadowed     the number of shadowed trials
	 * @param spotChecked  the number of spot-checked trials
	 * @param lenient      the number of trials with a lenient disagreement
	 * @param strict       the number of trials with a strict disagreement
	 * @param testVersion  the identity of the prompt test's definition
	 * @param labels       the frozen judge's label of each near-miss reply
	 * @param judgeVersion the version of CAT's judge
	 * @throws NullPointerException     if {@code profile}, {@code testVersion} or {@code labels} are null
	 * @throws IllegalArgumentException if a count is negative or a disagreement count exceeds the trials compared
	 */
	public CarriedJudge
	{
		requireThat(profile, "profile").isNotNull();
		requireThat(shadowed, "shadowed").isNotNegative();
		requireThat(spotChecked, "spotChecked").isNotNegative();
		requireThat(lenient, "lenient").isBetween(0, true, shadowed + spotChecked, true);
		requireThat(strict, "strict").isBetween(0, true, shadowed + spotChecked, true);
		requireThat(testVersion, "testVersion").isNotNull();
		requireThat(labels, "labels").isNotNull();
		labels = Map.copyOf(labels);
	}

	/**
	 * Indicates whether the qualification still applies: the test's definition, the frozen judge's near-miss labels,
	 * the judge version are unchanged and the profile is still one of the test's candidates.
	 *
	 * @param basis      the test's definition and judge version now
	 * @param labels     the frozen judge's near-miss labels now
	 * @param candidates the test's candidates now
	 * @return {@code true} if the qualification carries over
	 * @throws NullPointerException if any argument is null
	 */
	public boolean appliesTo(JudgeBasis basis, Map<String, Boolean> labels, List<TrialProfile> candidates)
	{
		requireThat(basis, "basis").isNotNull();
		requireThat(labels, "labels").isNotNull();
		requireThat(candidates, "candidates").isNotNull();
		return testVersion.equals(basis.testVersion()) && judgeVersion == basis.judgeVersion() &&
			this.labels.equals(labels) && candidates.contains(profile);
	}
}
