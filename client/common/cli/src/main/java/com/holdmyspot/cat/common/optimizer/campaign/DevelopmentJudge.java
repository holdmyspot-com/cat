package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge.Rejection;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.statistics.ClopperPearson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * How one prompt test chooses the profile that judges its development trials. The frozen judge's role profile is the
 * default; the test examines one cheaper candidate at a time, in order, and keeps the frozen judge once none is left.
 * <p>
 * A candidate first judges the test's near misses, which the frozen judge labeled once, and is rejected if it
 * disagrees with a label. It is then shadowed: it judges the development trials that the frozen judge also judges,
 * and the frozen judge's verdict counts. It qualifies when the one-sided 95% Clopper–Pearson upper bounds on its
 * lenient and its strict disagreement rates are at most {@value #LENIENT_LIMIT} and {@value #STRICT_LIMIT}, and is
 * rejected once either bound cannot reach its limit within {@value #SHADOWING_LIMIT} shadowed trials. After it
 * qualifies, spot checks that the frozen judge also judges join the same counts, and a bound above its limit returns
 * the test to the frozen judge for the rest of the campaign.
 * <p>
 * A trial counts once for each kind of disagreement it shows, however many assertions disagree in it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param labels      the frozen judge's label of each near-miss reply, by reply key: {@code true} if the frozen judge
 *                    passed it
 * @param candidates  the candidates not yet rejected, cheapest first; the first one is the candidate in examination
 *                    or in use
 * @param stage       how far the first candidate has come
 * @param rejected    the candidates that were turned down, in order
 * @param shadowed    the number of shadowed trials of the first candidate
 * @param spotChecked the number of spot-checked trials of the first candidate
 * @param lenient     the number of those trials in which the candidate passed an assertion that the frozen judge failed
 * @param strict      the number of those trials in which the candidate failed an assertion that the frozen judge
 *                    passed
 */
public record DevelopmentJudge(Map<String, Boolean> labels, List<TrialProfile> candidates, Stage stage,
	List<RejectedJudge> rejected, int shadowed, int spotChecked, int lenient, int strict)
{
	/**
	 * The largest upper bound on the lenient-disagreement rate of a qualified candidate.
	 */
	public static final double LENIENT_LIMIT = 0.01;
	/**
	 * The largest upper bound on the strict-disagreement rate of a qualified candidate.
	 */
	public static final double STRICT_LIMIT = 0.05;
	/**
	 * The number of shadowed trials within which a candidate must qualify.
	 */
	public static final int SHADOWING_LIMIT = 400;

	/**
	 * How far a prompt test's first candidate has come.
	 */
	public enum Stage
	{
		/**
		 * The candidate has not judged the near misses yet.
		 */
		NEAR_MISSES,
		/**
		 * The candidate shadows the frozen judge.
		 */
		SHADOWING,
		/**
		 * The candidate qualified and decides development trials, except for the spot checks.
		 */
		QUALIFIED,
		/**
		 * No candidate is left, or a spot check returned the test to the frozen judge.
		 */
		FROZEN
	}

	/**
	 * Creates a judge choice.
	 *
	 * @param labels      the frozen judge's label of each near-miss reply
	 * @param candidates  the candidates not yet rejected, cheapest first
	 * @param stage       how far the first candidate has come
	 * @param rejected    the candidates that were turned down, in order
	 * @param shadowed    the number of shadowed trials
	 * @param spotChecked the number of spot-checked trials
	 * @param lenient     the number of trials with a lenient disagreement
	 * @param strict      the number of trials with a strict disagreement
	 * @throws NullPointerException     if any collection or {@code stage} is null, or a collection holds null
	 * @throws IllegalArgumentException if a count is negative, a disagreement count exceeds the trials counted, or
	 *                                  {@code stage} needs a candidate and none is left
	 */
	public DevelopmentJudge
	{
		requireThat(labels, "labels").isNotNull();
		requireThat(candidates, "candidates").isNotNull();
		requireThat(stage, "stage").isNotNull();
		requireThat(rejected, "rejected").isNotNull();
		requireThat(shadowed, "shadowed").isNotNegative();
		requireThat(spotChecked, "spotChecked").isNotNegative();
		requireThat(lenient, "lenient").isBetween(0, true, shadowed + spotChecked, true);
		requireThat(strict, "strict").isBetween(0, true, shadowed + spotChecked, true);
		labels = Map.copyOf(labels);
		candidates = List.copyOf(candidates);
		rejected = List.copyOf(rejected);
		if (stage != Stage.FROZEN)
			requireThat(candidates, "candidates").isNotEmpty();
	}

	/**
	 * Starts the examination of a test's candidates once the frozen judge labeled its near misses.
	 *
	 * @param labels     the frozen judge's label of each near-miss reply
	 * @param candidates the candidates, cheapest first
	 * @return the test's judge choice, which keeps the frozen judge if there is no candidate
	 * @throws NullPointerException if any argument is null
	 */
	public static DevelopmentJudge start(Map<String, Boolean> labels, List<TrialProfile> candidates)
	{
		requireThat(candidates, "candidates").isNotNull();
		Stage stage = Stage.NEAR_MISSES;
		if (candidates.isEmpty())
			stage = Stage.FROZEN;
		return new DevelopmentJudge(labels, candidates, stage, List.of(), 0, 0, 0, 0);
	}

	/**
	 * Returns the candidate that is in examination or in use.
	 *
	 * @return the candidate, or empty if the test keeps the frozen judge
	 */
	public Optional<TrialProfile> current()
	{
		if (stage == Stage.FROZEN)
			return Optional.empty();
		return Optional.of(candidates.getFirst());
	}

	/**
	 * Returns the choice after a candidate judged the near misses.
	 *
	 * @param candidate the candidate that judged them
	 * @param agreed    {@code true} if it labeled every near miss as the frozen judge did
	 * @return the choice: shadowing if the candidate agreed, otherwise the next candidate; this choice if the
	 *   candidate is not the one in examination
	 * @throws NullPointerException if {@code candidate} is null
	 */
	public DevelopmentJudge nearMissesChecked(TrialProfile candidate, boolean agreed)
	{
		requireThat(candidate, "candidate").isNotNull();
		if (stage != Stage.NEAR_MISSES || !candidate.equals(candidates.getFirst()))
			return this;
		if (agreed)
			return new DevelopmentJudge(labels, candidates, Stage.SHADOWING, rejected, 0, 0, 0, 0);
		return reject(Rejection.NEAR_MISS);
	}

	/**
	 * Returns the choice after a trial that the frozen judge and the candidate both judged.
	 *
	 * @param candidate the candidate that judged the trial
	 * @param spotCheck {@code true} if the trial is a spot check of a qualified candidate rather than a shadowed trial
	 * @param lenient   {@code true} if the candidate passed an assertion that the frozen judge failed
	 * @param strict    {@code true} if the candidate failed an assertion that the frozen judge passed
	 * @return the choice, which no longer counts the trial's candidate if it was rejected, qualified or returned to the
	 *   frozen judge; this choice if the trial does not belong to the stage of the candidate in examination
	 * @throws NullPointerException if {@code candidate} is null
	 */
	public DevelopmentJudge trialShadowed(TrialProfile candidate, boolean spotCheck, boolean lenient, boolean strict)
	{
		requireThat(candidate, "candidate").isNotNull();
		Stage counted = Stage.SHADOWING;
		if (spotCheck)
			counted = Stage.QUALIFIED;
		if (stage != counted || !candidate.equals(candidates.getFirst()))
			return this;
		int newShadowed = shadowed;
		int newSpotChecked = spotChecked;
		if (spotCheck)
			++newSpotChecked;
		else
			++newShadowed;
		int newLenient = this.lenient;
		if (lenient)
			++newLenient;
		int newStrict = this.strict;
		if (strict)
			++newStrict;
		DevelopmentJudge counts = new DevelopmentJudge(labels, candidates, stage, rejected, newShadowed, newSpotChecked,
			newLenient, newStrict);
		int trials = newShadowed + newSpotChecked;
		if (spotCheck)
		{
			if (counts.meetsLimits(trials))
				return counts;
			return counts.reject(Rejection.SPOT_CHECK).returnToFrozen();
		}
		if (!counts.meetsLimits(SHADOWING_LIMIT))
			return counts.reject(Rejection.SHADOWING);
		if (counts.meetsLimits(trials))
			return new DevelopmentJudge(labels, candidates, Stage.QUALIFIED, rejected, newShadowed, 0, newLenient,
				newStrict);
		return counts;
	}

	/**
	 * Indicates whether both disagreement rates have an upper bound within their limits.
	 *
	 * @param trials the number of trials that the rates are over
	 * @return {@code true} if both bounds are within their limits
	 */
	private boolean meetsLimits(int trials)
	{
		return ClopperPearson.upperBoundAtMost(lenient, trials, LENIENT_LIMIT) &&
			ClopperPearson.upperBoundAtMost(strict, trials, STRICT_LIMIT);
	}

	/**
	 * Returns the choice without its first candidate, which is recorded as rejected, and with the next candidate in
	 * examination.
	 *
	 * @param reason why the first candidate is turned down
	 * @return the choice
	 */
	private DevelopmentJudge reject(Rejection reason)
	{
		List<RejectedJudge> turnedDown = new ArrayList<>(rejected);
		turnedDown.add(new RejectedJudge(candidates.getFirst(), reason, shadowed, spotChecked, lenient, strict));
		List<TrialProfile> remaining = candidates.subList(1, candidates.size());
		if (remaining.isEmpty())
			return new DevelopmentJudge(labels, remaining, Stage.FROZEN, turnedDown, 0, 0, 0, 0);
		return new DevelopmentJudge(labels, remaining, Stage.NEAR_MISSES, turnedDown, 0, 0, 0, 0);
	}

	/**
	 * Returns the choice that keeps the frozen judge and examines no further candidate.
	 *
	 * @return the choice
	 */
	private DevelopmentJudge returnToFrozen()
	{
		return new DevelopmentJudge(labels, List.of(), Stage.FROZEN, rejected, 0, 0, 0, 0);
	}
}
