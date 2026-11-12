package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.RejectedJudge.Rejection;
import com.holdmyspot.cat.common.optimizer.contract.TrialProfile;
import com.holdmyspot.cat.common.optimizer.statistics.ClopperPearson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * How one prompt test chooses the profile that judges its development trials. The frozen judge's role profile is the
 * default; the test examines cheaper candidates cheapest first and keeps the frozen judge once none is left.
 * <p>
 * A candidate first judges the test's near misses, which the frozen judge labeled once, and is rejected if it
 * disagrees with a label. It is then shadowed: it judges trials that the frozen judge also judges, and the frozen
 * judge's verdict counts. Those are the test's held-out trials, such as baseline and certification trials, which the
 * frozen judge judges anyway, and the development trials that are expected to bring it to qualification before the
 * next certification attempt. It qualifies when the one-sided 95% Clopper–Pearson upper bounds on its lenient and its
 * strict disagreement rates are at most {@value #LENIENT_LIMIT} and {@value #STRICT_LIMIT}, which takes at least
 * {@value #QUALIFYING_TRIALS} shadowed trials, and is rejected once either bound cannot reach its limit within
 * {@value #SHADOWING_LIMIT} shadowed trials.
 * <p>
 * The next candidate starts once the disagreements of every candidate in examination rule out qualifying at
 * {@value #QUALIFYING_TRIALS} shadowed trials; the earlier candidates keep shadowing until they are rejected, and the
 * first to qualify takes over the test and stops the others. After a candidate qualifies, spot checks that the frozen
 * judge also judges join the same counts, and a bound above its limit returns the test to the frozen judge for the
 * rest of the campaign.
 * <p>
 * A trial counts once for each kind of disagreement it shows, however many assertions disagree in it.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param labels            the frozen judge's label of each near-miss reply, by reply key: {@code true} if the frozen
 *                          judge passed it
 * @param waiting           the candidates that have not started, cheapest first
 * @param examined          the candidates in examination, or the one that qualified, in the order they started
 * @param rejected          the candidates that were turned down, in order
 * @param basis             the test's definition and the judge version that the choice rests on, or empty if the
 *                          campaign recorded none
 * @param developmentTrials the number of the test's development trials that were judged since the choice started
 * @param rounds            the number of development rounds that the campaign completed since the choice started
 */
public record DevelopmentJudge(Map<String, Boolean> labels, List<TrialProfile> waiting, List<ExaminedJudge> examined,
	List<RejectedJudge> rejected, Optional<JudgeBasis> basis, int developmentTrials, int rounds)
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
	 * The fewest shadowed trials with which a candidate qualifies, which it does when none of them disagrees.
	 */
	public static final int QUALIFYING_TRIALS = 299;
	/**
	 * The number of shadowed trials within which a candidate must qualify.
	 */
	public static final int SHADOWING_LIMIT = 400;

	/**
	 * How far a prompt test, or one of its candidates, has come.
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
	 * @param labels            the frozen judge's label of each near-miss reply
	 * @param waiting           the candidates that have not started, cheapest first
	 * @param examined          the candidates in examination, or the one that qualified
	 * @param rejected          the candidates that were turned down, in order
	 * @param basis             the test's definition and the judge version, or empty
	 * @param developmentTrials the number of development trials judged since the choice started
	 * @param rounds            the number of development rounds completed since the choice started
	 * @throws NullPointerException     if any argument is null, or a collection holds null
	 * @throws IllegalArgumentException if a count is negative, a profile is both waiting and examined or is examined
	 *                                  twice, or a qualified candidate is not the only one left
	 */
	public DevelopmentJudge
	{
		requireThat(labels, "labels").isNotNull();
		requireThat(waiting, "waiting").isNotNull();
		requireThat(examined, "examined").isNotNull();
		requireThat(rejected, "rejected").isNotNull();
		requireThat(basis, "basis").isNotNull();
		requireThat(developmentTrials, "developmentTrials").isNotNegative();
		requireThat(rounds, "rounds").isNotNegative();
		labels = Map.copyOf(labels);
		waiting = List.copyOf(waiting);
		examined = List.copyOf(examined);
		rejected = List.copyOf(rejected);
		Set<TrialProfile> profiles = new HashSet<>(waiting);
		for (ExaminedJudge judge : examined)
		{
			requireThat(profiles.add(judge.profile()), "profiles.add(judge.profile())").
				withContext(judge.profile(), "profile").isTrue();
			if (judge.stage() == Stage.QUALIFIED)
			{
				requireThat(examined.size(), "examined.size()").isEqualTo(1);
				requireThat(waiting, "waiting").isEmpty();
			}
		}
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
		return start(labels, candidates, Optional.empty(), Optional.empty());
	}

	/**
	 * Starts the examination of a test's candidates once the frozen judge labeled its near misses, with the
	 * qualification that an earlier campaign on the same target carried over if it still applies.
	 *
	 * @param labels     the frozen judge's label of each near-miss reply
	 * @param candidates the candidates, cheapest first
	 * @param basis      the test's definition and the judge version, or empty if they are unknown
	 * @param carried    the qualification that an earlier campaign recorded for the test, or empty if there is none
	 * @return the test's judge choice: the carried candidate qualified with its counts if the qualification applies,
	 *   otherwise the first candidate at its near misses, or the frozen judge if there is no candidate
	 * @throws NullPointerException if any argument is null
	 */
	public static DevelopmentJudge start(Map<String, Boolean> labels, List<TrialProfile> candidates,
		Optional<JudgeBasis> basis, Optional<CarriedJudge> carried)
	{
		requireThat(candidates, "candidates").isNotNull();
		requireThat(basis, "basis").isNotNull();
		requireThat(carried, "carried").isNotNull();
		if (carried.isPresent() && basis.isPresent() && carried.get().appliesTo(basis.get(), labels, candidates))
		{
			CarriedJudge qualified = carried.get();
			ExaminedJudge judge = new ExaminedJudge(qualified.profile(), Stage.QUALIFIED, qualified.shadowed(), 0,
				qualified.spotChecked(), qualified.lenient(), qualified.strict());
			return new DevelopmentJudge(labels, List.of(), List.of(judge), List.of(), basis, 0, 0);
		}
		return new DevelopmentJudge(labels, candidates, List.of(), List.of(), basis, 0, 0).admit();
	}

	/**
	 * Returns how far the test has come.
	 *
	 * @return {@link Stage#QUALIFIED} if a candidate qualified, {@link Stage#FROZEN} if no candidate is left,
	 *   {@link Stage#SHADOWING} if a candidate is shadowed, otherwise {@link Stage#NEAR_MISSES}
	 */
	public Stage stage()
	{
		if (examined.isEmpty())
			return Stage.FROZEN;
		Stage stage = Stage.NEAR_MISSES;
		for (ExaminedJudge judge : examined)
		{
			if (judge.stage() == Stage.QUALIFIED)
				return Stage.QUALIFIED;
			if (judge.stage() == Stage.SHADOWING)
				stage = Stage.SHADOWING;
		}
		return stage;
	}

	/**
	 * Returns the candidate that qualified.
	 *
	 * @return the candidate, or empty if none qualified
	 */
	public Optional<ExaminedJudge> qualified()
	{
		return examined.stream().filter(judge -> judge.stage() == Stage.QUALIFIED).findFirst();
	}

	/**
	 * Returns the candidates that are shadowed.
	 *
	 * @return the candidates, in the order they started
	 */
	public List<ExaminedJudge> shadowing()
	{
		return examined.stream().filter(judge -> judge.stage() == Stage.SHADOWING).toList();
	}

	/**
	 * Returns the candidates that have not judged the near misses yet.
	 *
	 * @return the candidates, in the order they started
	 */
	public List<TrialProfile> atNearMisses()
	{
		return examined.stream().filter(judge -> judge.stage() == Stage.NEAR_MISSES).map(ExaminedJudge::profile).
			toList();
	}

	/**
	 * Returns the candidate that qualified or, before any did, the first candidate in examination.
	 *
	 * @return the candidate, or empty if the test keeps the frozen judge
	 */
	public Optional<TrialProfile> current()
	{
		return lead().map(ExaminedJudge::profile);
	}

	/**
	 * Returns the number of shadowed trials of the {@link #current() current} candidate.
	 *
	 * @return the number, or 0 if there is no candidate
	 */
	public int shadowed()
	{
		return lead().map(ExaminedJudge::shadowed).orElse(0);
	}

	/**
	 * Returns the number of shadowed trials of the {@link #current() current} candidate that were not development
	 * trials.
	 *
	 * @return the number, or 0 if there is no candidate
	 */
	public int heldOut()
	{
		return lead().map(ExaminedJudge::heldOut).orElse(0);
	}

	/**
	 * Returns the number of spot-checked trials of the {@link #current() current} candidate.
	 *
	 * @return the number, or 0 if there is no candidate
	 */
	public int spotChecked()
	{
		return lead().map(ExaminedJudge::spotChecked).orElse(0);
	}

	/**
	 * Returns the number of trials with a lenient disagreement of the {@link #current() current} candidate.
	 *
	 * @return the number, or 0 if there is no candidate
	 */
	public int lenient()
	{
		return lead().map(ExaminedJudge::lenient).orElse(0);
	}

	/**
	 * Returns the number of trials with a strict disagreement of the {@link #current() current} candidate.
	 *
	 * @return the number, or 0 if there is no candidate
	 */
	public int strict()
	{
		return lead().map(ExaminedJudge::strict).orElse(0);
	}

	/**
	 * Returns the candidate that qualified or, before any did, the first candidate in examination.
	 *
	 * @return the candidate, or empty if there is none
	 */
	private Optional<ExaminedJudge> lead()
	{
		Optional<ExaminedJudge> qualified = qualified();
		if (qualified.isPresent())
			return qualified;
		return examined.stream().findFirst();
	}

	/**
	 * Returns the choice after a candidate judged the near misses.
	 *
	 * @param candidate the candidate that judged them
	 * @param agreed    {@code true} if it labeled every near miss as the frozen judge did
	 * @return the choice: the candidate shadowing if it agreed, otherwise rejected; this choice if the candidate is not
	 *   at its near misses
	 * @throws NullPointerException if {@code candidate} is null
	 */
	public DevelopmentJudge nearMissesChecked(TrialProfile candidate, boolean agreed)
	{
		requireThat(candidate, "candidate").isNotNull();
		Optional<ExaminedJudge> judge = find(candidate).filter(found -> found.stage() == Stage.NEAR_MISSES);
		if (judge.isEmpty())
			return this;
		if (agreed)
			return replace(judge.get(), judge.get().at(Stage.SHADOWING)).admit();
		return reject(judge.get(), Rejection.NEAR_MISS).admit();
	}

	/**
	 * Returns the choice after a trial that the frozen judge and a candidate both judged.
	 *
	 * @param shadow how the candidate's verdicts compared with the frozen judge's
	 * @return the choice: the candidate rejected, qualified or returned to the frozen judge if the trial decides it,
	 *   with the next candidate started if no candidate in examination can still qualify at
	 *   {@value #QUALIFYING_TRIALS} shadowed trials; this choice if the candidate is not at the stage that the trial
	 *   counts for
	 * @throws NullPointerException if {@code shadow} is null
	 */
	public DevelopmentJudge trialShadowed(ShadowResult shadow)
	{
		requireThat(shadow, "shadow").isNotNull();
		Stage counted = Stage.SHADOWING;
		if (shadow.spotCheck())
			counted = Stage.QUALIFIED;
		Optional<ExaminedJudge> found = find(shadow.candidate());
		if (found.isEmpty() || found.get().stage() != counted)
			return this;
		ExaminedJudge judge = found.get().plus(shadow);
		if (shadow.spotCheck())
		{
			if (meetsLimits(judge, judge.compared()))
				return replace(found.get(), judge);
			List<RejectedJudge> turnedDown = new ArrayList<>(rejected);
			turnedDown.add(judge.rejected(Rejection.SPOT_CHECK));
			return new DevelopmentJudge(labels, List.of(), List.of(), turnedDown, basis, developmentTrials, rounds);
		}
		if (!meetsLimits(judge, SHADOWING_LIMIT))
			return reject(judge, Rejection.SHADOWING).admit();
		if (meetsLimits(judge, judge.shadowed()))
		{
			return new DevelopmentJudge(labels, List.of(), List.of(judge.at(Stage.QUALIFIED)), rejected, basis,
				developmentTrials, rounds);
		}
		return replace(found.get(), judge).admit();
	}

	/**
	 * Returns the choice after one of the test's development trials was judged.
	 *
	 * @return the choice with the trial counted
	 */
	public DevelopmentJudge developmentTrialJudged()
	{
		return new DevelopmentJudge(labels, waiting, examined, rejected, basis, developmentTrials + 1, rounds);
	}

	/**
	 * Returns the choice after the campaign completed a development round.
	 *
	 * @return the choice with the round counted
	 */
	public DevelopmentJudge roundCompleted()
	{
		return new DevelopmentJudge(labels, waiting, examined, rejected, basis, developmentTrials, rounds + 1);
	}

	/**
	 * Returns the number of development trials that the test is expected to run before the next certification
	 * attempt: at least the trials that the commit gate still needs, and at least one more round like the average
	 * round so far.
	 *
	 * @param gateTrials the development trials that the test's cells still need to meet the commit gate
	 * @return the expected number of trials
	 * @throws IllegalArgumentException if {@code gateTrials} is negative
	 */
	public int expectedDevelopmentTrials(int gateTrials)
	{
		requireThat(gateTrials, "gateTrials").isNotNegative();
		int perRound = 0;
		if (rounds > 0)
			perRound = developmentTrials / rounds;
		return Math.max(gateTrials, perRound);
	}

	/**
	 * Indicates whether shadowing development trials can bring a candidate to qualification: the fewest further
	 * shadowed trials with which its current disagreements could qualify, under the bounds that decide qualification,
	 * is at most the development trials that the test is expected to run.
	 *
	 * @param candidate the shadowed candidate
	 * @param expected  the development trials that the test is expected to run before the next certification attempt
	 * @return {@code true} if live shadowing can pay off
	 * @throws NullPointerException     if {@code candidate} is null
	 * @throws IllegalArgumentException if {@code expected} is negative
	 */
	public static boolean paysOff(ExaminedJudge candidate, int expected)
	{
		requireThat(candidate, "candidate").isNotNull();
		requireThat(expected, "expected").isNotNegative();
		OptionalInt fewest = fewestQualifyingTrials(candidate);
		return fewest.isPresent() && fewest.getAsInt() - candidate.shadowed() <= expected;
	}

	/**
	 * Returns the fewest shadowed trials with which a candidate's current disagreements qualify, if no further trial
	 * disagrees.
	 *
	 * @param candidate the candidate
	 * @return the number of trials, or empty if they cannot qualify within {@value #SHADOWING_LIMIT}
	 * @throws NullPointerException if {@code candidate} is null
	 */
	public static OptionalInt fewestQualifyingTrials(ExaminedJudge candidate)
	{
		requireThat(candidate, "candidate").isNotNull();
		for (int trials = Math.max(candidate.shadowed(), 1); trials <= SHADOWING_LIMIT; ++trials)
		{
			if (meetsLimits(candidate, trials))
				return OptionalInt.of(trials);
		}
		return OptionalInt.empty();
	}

	/**
	 * Indicates whether a candidate in examination can still qualify at {@value #QUALIFYING_TRIALS} shadowed trials,
	 * so that the next candidate need not start yet.
	 *
	 * @param candidate the candidate
	 * @return {@code true} if it has not judged its near misses, or it is shadowed and its disagreements allow it to
	 *   qualify at {@value #QUALIFYING_TRIALS} shadowed trials
	 */
	private static boolean isPromising(ExaminedJudge candidate)
	{
		return switch (candidate.stage())
		{
			case NEAR_MISSES -> true;
			case SHADOWING -> candidate.shadowed() < QUALIFYING_TRIALS &&
				meetsLimits(candidate, QUALIFYING_TRIALS);
			case QUALIFIED, FROZEN -> false;
		};
	}

	/**
	 * Indicates whether both disagreement rates of a candidate have an upper bound within their limits.
	 *
	 * @param candidate the candidate
	 * @param trials    the number of trials that the rates are over
	 * @return {@code true} if both bounds are within their limits
	 */
	private static boolean meetsLimits(ExaminedJudge candidate, int trials)
	{
		return trials > 0 && ClopperPearson.upperBoundAtMost(candidate.lenient(), trials, LENIENT_LIMIT) &&
			ClopperPearson.upperBoundAtMost(candidate.strict(), trials, STRICT_LIMIT);
	}

	/**
	 * Starts the next waiting candidates until a candidate in examination can still qualify at
	 * {@value #QUALIFYING_TRIALS} shadowed trials or none is waiting.
	 *
	 * @return the choice
	 */
	private DevelopmentJudge admit()
	{
		if (qualified().isPresent())
			return this;
		List<TrialProfile> remaining = new ArrayList<>(waiting);
		List<ExaminedJudge> started = new ArrayList<>(examined);
		while (!remaining.isEmpty() && started.stream().noneMatch(DevelopmentJudge::isPromising))
			started.add(ExaminedJudge.started(remaining.removeFirst()));
		if (started.size() == examined.size())
			return this;
		return new DevelopmentJudge(labels, remaining, started, rejected, basis, developmentTrials, rounds);
	}

	/**
	 * Finds a candidate in examination.
	 *
	 * @param profile the candidate's profile
	 * @return the candidate, or empty if it is not in examination
	 */
	private Optional<ExaminedJudge> find(TrialProfile profile)
	{
		return examined.stream().filter(judge -> judge.profile().equals(profile)).findFirst();
	}

	/**
	 * Returns the choice with a candidate in examination replaced.
	 *
	 * @param before the candidate
	 * @param after  its replacement
	 * @return the choice
	 */
	private DevelopmentJudge replace(ExaminedJudge before, ExaminedJudge after)
	{
		List<ExaminedJudge> replaced = new ArrayList<>(examined);
		replaced.set(replaced.indexOf(before), after);
		return new DevelopmentJudge(labels, waiting, replaced, rejected, basis, developmentTrials, rounds);
	}

	/**
	 * Returns the choice without a candidate in examination, which is recorded as rejected.
	 *
	 * @param judge  the candidate with its final counts
	 * @param reason why it is turned down
	 * @return the choice
	 */
	private DevelopmentJudge reject(ExaminedJudge judge, Rejection reason)
	{
		List<ExaminedJudge> remaining = new ArrayList<>(examined);
		remaining.removeIf(candidate -> candidate.profile().equals(judge.profile()));
		List<RejectedJudge> turnedDown = new ArrayList<>(rejected);
		turnedDown.add(judge.rejected(reason));
		return new DevelopmentJudge(labels, waiting, remaining, turnedDown, basis, developmentTrials, rounds);
	}
}
