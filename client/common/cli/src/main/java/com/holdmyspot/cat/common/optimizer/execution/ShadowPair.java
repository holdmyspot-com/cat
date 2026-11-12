package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.ShadowResult;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

import java.util.HashSet;
import java.util.Set;

/**
 * The two judge sessions of one trial that a candidate development judge shadows: the frozen judge, whose verdict
 * counts, and the candidate, whose verdict is only compared with it.
 * <p>
 * The pair is complete once the frozen judge decided and the candidate either decided or could not produce a usable
 * verdict. A pair that the frozen judge's failure abandoned ignores the candidate's result.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. The driver's thread is its only user.
 */
final class ShadowPair
{
	private final TrialProfile frozenProfile;
	private final TrialProfile candidate;
	private final boolean spotCheck;
	private Judgment frozen;
	private Judgment compared;
	private boolean unusable;
	private boolean abandoned;

	/**
	 * Creates a pair whose judges have not decided yet.
	 *
	 * @param frozenProfile the frozen judge's profile
	 * @param candidate     the candidate's profile
	 * @param spotCheck     {@code true} if the pair spot-checks a qualified candidate instead of shadowing it
	 * @throws NullPointerException if any of the arguments are null
	 */
	ShadowPair(TrialProfile frozenProfile, TrialProfile candidate, boolean spotCheck)
	{
		requireThat(frozenProfile, "frozenProfile").isNotNull();
		requireThat(candidate, "candidate").isNotNull();
		this.frozenProfile = frozenProfile;
		this.candidate = candidate;
		this.spotCheck = spotCheck;
	}

	/**
	 * Returns the frozen judge's profile.
	 *
	 * @return the profile
	 */
	TrialProfile frozenProfile()
	{
		return frozenProfile;
	}

	/**
	 * Returns the candidate's profile.
	 *
	 * @return the profile
	 */
	TrialProfile candidate()
	{
		return candidate;
	}

	/**
	 * Returns the frozen judge's verdict.
	 *
	 * @return the verdict, or {@code null} if the frozen judge has not decided
	 */
	Judgment frozen()
	{
		return frozen;
	}

	/**
	 * Records the frozen judge's verdict.
	 *
	 * @param judgment the verdict
	 * @throws NullPointerException if {@code judgment} is null
	 */
	void recordFrozen(Judgment judgment)
	{
		requireThat(judgment, "judgment").isNotNull();
		frozen = judgment;
	}

	/**
	 * Records the candidate's verdict.
	 *
	 * @param judgment the verdict
	 * @throws NullPointerException if {@code judgment} is null
	 */
	void recordCandidate(Judgment judgment)
	{
		requireThat(judgment, "judgment").isNotNull();
		compared = judgment;
	}

	/**
	 * Records that the candidate produced no usable verdict.
	 */
	void recordUnusable()
	{
		unusable = true;
	}

	/**
	 * Drops the pair because the frozen judge produced no verdict, so that the trial reruns.
	 */
	void abandon()
	{
		abandoned = true;
	}

	/**
	 * Indicates whether the frozen judge's failure dropped the pair.
	 *
	 * @return {@code true} if the pair is abandoned
	 */
	boolean isAbandoned()
	{
		return abandoned;
	}

	/**
	 * Indicates whether both judges are done.
	 *
	 * @return {@code true} if the frozen judge decided and the candidate decided or failed to
	 */
	boolean isComplete()
	{
		return !abandoned && frozen != null && (compared != null || unusable);
	}

	/**
	 * Compares the candidate's verdict with the frozen judge's. The candidate is lenient on an assertion that it
	 * passes and the frozen judge fails, and strict on one that it fails and the frozen judge passes. A candidate
	 * without a usable verdict counts as strict.
	 *
	 * @return the comparison
	 * @throws IllegalStateException if the pair is not complete
	 */
	ShadowResult result()
	{
		if (!isComplete())
			throw new IllegalStateException("The pair is not complete");
		if (compared == null)
			return new ShadowResult(candidate, spotCheck, false, true);
		Set<String> frozenFailures = failedIds(frozen);
		Set<String> candidateFailures = failedIds(compared);
		boolean lenient = !candidateFailures.containsAll(frozenFailures);
		boolean strict = !frozenFailures.containsAll(candidateFailures);
		return new ShadowResult(candidate, spotCheck, lenient, strict);
	}

	/**
	 * Returns the assertions that a verdict fails.
	 *
	 * @param judgment the verdict
	 * @return the identifiers of the failed assertions
	 */
	private static Set<String> failedIds(Judgment judgment)
	{
		Set<String> ids = new HashSet<>();
		for (FailedAssertion failure : judgment.failures())
			ids.add(failure.assertionId());
		return ids;
	}
}
