package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.FailedAssertion;
import com.holdmyspot.cat.common.optimizer.campaign.ShadowResult;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The judge sessions of one trial that candidate development judges shadow: the frozen judge, whose verdict counts,
 * and one session for each candidate, whose verdict is only compared with it.
 * <p>
 * The group is complete once the frozen judge decided and every candidate either decided or could not produce a
 * usable verdict. A group that the frozen judge's failure abandoned ignores the candidates' results.
 * <p>
 * <b>Thread Safety:</b> This class is not thread-safe. The driver's thread is its only user.
 */
final class ShadowGroup
{
	private final TrialProfile frozenProfile;
	private final List<TrialProfile> candidates;
	private final boolean spotCheck;
	private final boolean heldOut;
	private final Map<TrialProfile, Judgment> compared = new HashMap<>();
	private final Set<TrialProfile> unusable = new HashSet<>();
	private Judgment frozen;
	private boolean abandoned;

	/**
	 * Creates a group whose judges have not decided yet.
	 *
	 * @param frozenProfile the frozen judge's profile
	 * @param candidates    the candidates' profiles, in the order they started
	 * @param spotCheck     {@code true} if the group spot-checks a qualified candidate instead of shadowing it
	 * @param heldOut       {@code true} if the trial is not a development trial
	 * @throws NullPointerException     if {@code frozenProfile} or {@code candidates} are null
	 * @throws IllegalArgumentException if {@code candidates} is empty, or a held-out trial is a spot check
	 */
	ShadowGroup(TrialProfile frozenProfile, List<TrialProfile> candidates, boolean spotCheck, boolean heldOut)
	{
		requireThat(frozenProfile, "frozenProfile").isNotNull();
		requireThat(candidates, "candidates").isNotEmpty();
		if (spotCheck)
			requireThat(heldOut, "heldOut").isFalse();
		this.frozenProfile = frozenProfile;
		this.candidates = List.copyOf(candidates);
		this.spotCheck = spotCheck;
		this.heldOut = heldOut;
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
	 * Returns the candidates' profiles.
	 *
	 * @return the profiles, in the order they started
	 */
	List<TrialProfile> candidates()
	{
		return candidates;
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
	 * Records a candidate's verdict.
	 *
	 * @param candidate the candidate's profile
	 * @param judgment  the verdict
	 * @throws NullPointerException if any of the arguments are null
	 */
	void recordCandidate(TrialProfile candidate, Judgment judgment)
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(judgment, "judgment").isNotNull();
		compared.put(candidate, judgment);
	}

	/**
	 * Records that a candidate produced no usable verdict.
	 *
	 * @param candidate the candidate's profile
	 * @throws NullPointerException if {@code candidate} is null
	 */
	void recordUnusable(TrialProfile candidate)
	{
		requireThat(candidate, "candidate").isNotNull();
		unusable.add(candidate);
	}

	/**
	 * Drops the group because the frozen judge produced no verdict, so that the trial reruns.
	 */
	void abandon()
	{
		abandoned = true;
	}

	/**
	 * Indicates whether the frozen judge's failure dropped the group.
	 *
	 * @return {@code true} if the group is abandoned
	 */
	boolean isAbandoned()
	{
		return abandoned;
	}

	/**
	 * Indicates whether every judge is done.
	 *
	 * @return {@code true} if the frozen judge decided and every candidate decided or failed to
	 */
	boolean isComplete()
	{
		if (abandoned || frozen == null)
			return false;
		for (TrialProfile candidate : candidates)
		{
			if (!compared.containsKey(candidate) && !unusable.contains(candidate))
				return false;
		}
		return true;
	}

	/**
	 * Compares each candidate's verdict with the frozen judge's. A candidate is lenient on an assertion that it
	 * passes and the frozen judge fails, and strict on one that it fails and the frozen judge passes. A candidate
	 * without a usable verdict counts as strict.
	 *
	 * @return the comparisons, in the order the candidates started
	 * @throws IllegalStateException if the group is not complete
	 */
	List<ShadowResult> results()
	{
		if (!isComplete())
			throw new IllegalStateException("The group is not complete");
		Set<String> frozenFailures = failedIds(frozen);
		List<ShadowResult> results = new ArrayList<>(candidates.size());
		for (TrialProfile candidate : candidates)
		{
			Judgment judgment = compared.get(candidate);
			if (judgment == null)
			{
				results.add(new ShadowResult(candidate, spotCheck, heldOut, false, true));
				continue;
			}
			Set<String> candidateFailures = failedIds(judgment);
			boolean lenient = !candidateFailures.containsAll(frozenFailures);
			boolean strict = !frozenFailures.containsAll(candidateFailures);
			results.add(new ShadowResult(candidate, spotCheck, heldOut, lenient, strict));
		}
		return results;
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
