package com.holdmyspot.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.contract.Generator;
import com.holdmyspot.cat.common.optimizer.contract.DrawnTestCase;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;
import com.holdmyspot.cat.common.secrets.PromotionAccess;
import com.holdmyspot.cat.common.secrets.PromotionSecrets;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans promotion replays and decides whether a proposed edit replaces the working candidate.
 * <p>
 * A triggering test case is a failure in the edited cell, the prompt test under a runner profile. The edit starts each
 * triggering test case with two replays. An edit fixes a triggering test case when every replay passes and its passing
 * streak has probability below 10% under the working candidate's smoothed observed pass rate,
 * {@code (passes + 1) / (outcomes + 2)} on the same test case. A passing streak extends one replay at a time until it
 * is unlikely, so the more often the working candidate passes a test case, the more replays a fix needs there; the
 * smoothed rate is below 1, so every streak that keeps passing ends in a fix.
 * <p>
 * Once some triggering test case has two passes and no failure, or at once for a minimality reduction, the edit runs
 * each other test case in the regression set once, and the working candidate runs each of those test cases on which it
 * has no outcome. The regression replays of each prompt test form a group. A group passes when none of its replays
 * fails; it regresses when its failures would occur with probability below 5%, {@code 1 / REGRESSION_BAR}, at the
 * working candidate's smoothed pass rate on the group's test cases. Otherwise the edit and the working candidate run
 * {@link #PAIRED_REPLAY_STEP} paired replays of the group's test cases, and the group is judged again, until it
 * regresses or has had {@link #MAXIMUM_PAIRED_REPLAYS} paired replays and passes.
 * <p>
 * The edit replaces the working candidate when it fixes at least one triggering test case and every group passes; it
 * is rejected as soon as every fix is impossible or a group regresses. A minimality reduction must preserve every
 * triggering fix in the last accepted replays. These replays reuse fixed test cases and never count as representative
 * evidence.
 */
public final class Promotion
{
	static
	{
		PromotionSecrets.setPromotionAccess(new PromotionAccess()
		{
			@Override
			public boolean isSameTestCase(DrawnTestCase first, DrawnTestCase second)
			{
				return Promotion.isSameTestCase(first, second);
			}
		});
	}

	/**
	 * The initial number of promotion replays planned for each distinct triggering test case.
	 */
	public static final int STARTING_TRIGGERING_REPLAYS = 2;
	/**
	 * The probability below which a group's regression failures show that the edit is worse than the working candidate,
	 * as a denominator: the bar is {@code 1 / 20}, or 5%. Simulated at a 93% working pass rate, the regression check with
	 * this bar, {@link #PAIRED_REPLAY_STEP} and {@link #MAXIMUM_PAIRED_REPLAYS} rejects an equally good edit about 2.4%
	 * of the time per prompt test and an edit that passes 80% about 38% of the time.
	 */
	private static final int REGRESSION_BAR = 20;
	/**
	 * The number of paired replays that a group receives before it is judged again. Simulated at a 93% working pass
	 * rate, the regression check with this step, {@link #REGRESSION_BAR} and {@link #MAXIMUM_PAIRED_REPLAYS} rejects an
	 * equally good edit about 2.4% of the time per prompt test and an edit that passes 80% about 38% of the time.
	 */
	public static final int PAIRED_REPLAY_STEP = 5;
	/**
	 * The number of paired replays after which a group that has not regressed passes. Simulated at a 93% working pass
	 * rate, the regression check with this limit, {@link #REGRESSION_BAR} and {@link #PAIRED_REPLAY_STEP} rejects an
	 * equally good edit about 2.4% of the time per prompt test and an edit that passes 80% about 38% of the time.
	 */
	public static final int MAXIMUM_PAIRED_REPLAYS = 15;

	/**
	 * The state of a prompt author attempt's promotion.
	 */
	public enum Decision
	{
		/**
		 * Some replay that the decision needs has no outcome yet.
		 */
		PENDING,
		/**
		 * The attempt becomes the working candidate.
		 */
		PROMOTE,
		/**
		 * The attempt is not promotable.
		 */
		REJECT
	}

	/**
	 * Prevents instantiation.
	 */
	private Promotion()
	{
	}

	/**
	 * Plans the triggering replays of a prompt author attempt for all triggering cells of one addressed test. The
	 * regression replays follow once {@link #extend} finds them due.
	 *
	 * @param cells the triggering cells
	 * @param failures the failing test cases that the prompt author received; only those in {@code cells} trigger replays
	 * @param generator the contract's generator, which marks the test cases targeted
	 * @return the unassigned replays, {@link #STARTING_TRIGGERING_REPLAYS} for each distinct triggering test case
	 * @throws NullPointerException if any argument is null or {@code failures} contains null
	 * @throws IllegalArgumentException if {@code cells} is empty
	 */
	public static List<PromotionReplay> plan(List<String> cells, List<DrawnTestCase> failures, Generator generator)
	{
		requireThat(cells, "cells").isNotEmpty();
		requireThat(failures, "failures").isNotNull();
		requireThat(generator, "generator").isNotNull();
		List<PromotionReplay> triggering = new ArrayList<>();
		List<DrawnTestCase> triggeringTestCases = new ArrayList<>();
		for (DrawnTestCase failure : failures)
		{
			if (!cells.contains(failure.cellId()))
				continue;
			if (containsTestCase(triggeringTestCases, failure))
				continue;
			DrawnTestCase testCase = generator.markTargeted(failure);
			triggeringTestCases.add(testCase);
			for (int replay = 0; replay < STARTING_TRIGGERING_REPLAYS; ++replay)
				triggering.add(new PromotionReplay(PromotionReplay.Role.TRIGGERING, testCase, null, null));
		}
		return triggering;
	}

	/**
	 * Plans the replays that the attempt's adopted outcomes make due: one extra replay for each fully passing triggering
	 * streak that is still likely; the regression replays and the working candidate's missing reference replays once they
	 * are due and not yet planned; and {@link #PAIRED_REPLAY_STEP} paired replays for each group whose last check neither
	 * passed nor regressed. A group's paired replays cycle through its test cases in regression replay order, those the
	 * attempt failed first, and each paired replay of the attempt is followed by the working candidate's paired replay of
	 * the same test case.
	 *
	 * @param attempt the attempt whose adopted outcomes determine extensions
	 * @param regressionSet the lineage's regression set
	 * @param workingOutcomes the working candidate's adopted outcomes, aggregated by test case
	 * @param sharedReferences the test cases on which another replay already measures the working candidate, so that the
	 *   attempt plans no reference replay of its own there
	 * @return the attempt with unassigned replays appended, or the same attempt if none is due
	 * @throws NullPointerException if an argument or element is null
	 */
	public static PromptAuthorAttempt extend(PromptAuthorAttempt attempt, List<RegressionTestCase> regressionSet,
		List<TestCaseOutcomes> workingOutcomes, List<DrawnTestCase> sharedReferences)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();
		requireThat(sharedReferences, "sharedReferences").isNotNull();

		List<PromotionReplay> replays = new ArrayList<>(attempt.replays());
		for (DrawnTestCase testCase : triggeringTestCases(attempt.replays()))
		{
			List<PromotionReplay> streak = attempt.replays().stream().filter(replay ->
				replay.role() == PromotionReplay.Role.TRIGGERING && isSameTestCase(testCase, replay.testCase())).toList();
			if (streak.size() >= STARTING_TRIGGERING_REPLAYS &&
				streak.stream().allMatch(replay -> replay.verdict() == Verdict.PASS) &&
				!isUnlikely(testCase, streak.size(), workingOutcomes))
			{
				replays.add(new PromotionReplay(PromotionReplay.Role.TRIGGERING, testCase, null, null));
			}
		}
		if (isRegressionUnplanned(attempt))
		{
			for (PromotionReplay replay : regressionReplays(attempt, regressionSet))
			{
				if (replay.role() == PromotionReplay.Role.REGRESSION || !containsTestCase(sharedReferences, replay.testCase()))
					replays.add(replay);
			}
		}
		for (String testId : regressionGroups(attempt.replays()))
		{
			if (checkGroup(testId, attempt, regressionSet) != GroupCheck.NEEDS_PAIRED_REPLAYS)
				continue;
			List<DrawnTestCase> order = pairedReplayOrder(testId, attempt.replays());
			long planned = attempt.replays().stream().filter(replay ->
				replay.role() == PromotionReplay.Role.PAIRED_REGRESSION && replay.testCase().testId().equals(testId)).count();
			for (long pair = planned; pair < planned + PAIRED_REPLAY_STEP; ++pair)
			{
				DrawnTestCase testCase = order.get((int) (pair % order.size()));
				replays.add(new PromotionReplay(PromotionReplay.Role.PAIRED_REGRESSION, testCase, null, null));
				replays.add(new PromotionReplay(PromotionReplay.Role.PAIRED_WORKING, testCase, null, null));
			}
		}
		if (replays.size() == attempt.replays().size())
			return attempt;
		return new PromptAuthorAttempt(attempt.number(), attempt.cell(), attempt.snapshot(), replays,
			attempt.minimality());
	}

	/**
	 * Indicates whether the attempt's regression replays are due but not planned. They are due for a minimality
	 * reduction, and for an original attempt once some triggering test case has {@link #STARTING_TRIGGERING_REPLAYS}
	 * passes and no failure.
	 *
	 * @param attempt the attempt
	 * @return {@code true} if the attempt has no regression replay and its regression replays are due
	 */
	private static boolean isRegressionUnplanned(PromptAuthorAttempt attempt)
	{
		if (attempt.replays().stream().anyMatch(replay -> replay.role() == PromotionReplay.Role.REGRESSION))
			return false;
		if (attempt.minimality().testing() != null)
			return true;
		for (DrawnTestCase testCase : triggeringTestCases(attempt.replays()))
		{
			int passes = 0;
			boolean failed = false;
			for (PromotionReplay replay : attempt.replays())
			{
				if (replay.role() != PromotionReplay.Role.TRIGGERING || !isSameTestCase(testCase, replay.testCase()))
					continue;
				if (replay.verdict() == Verdict.FAIL)
					failed = true;
				else if (replay.verdict() == Verdict.PASS)
					++passes;
			}
			if (!failed && passes >= STARTING_TRIGGERING_REPLAYS)
				return true;
		}
		return false;
	}

	/**
	 * Returns the regression replays of an attempt: one replay of each regression test case that does not trigger the
	 * attempt, then one reference replay of the working candidate on each such test case on which it has no outcome.
	 *
	 * @param attempt the attempt
	 * @param regressionSet the lineage's regression set
	 * @return the unassigned replays
	 */
	private static List<PromotionReplay> regressionReplays(PromptAuthorAttempt attempt,
		List<RegressionTestCase> regressionSet)
	{
		List<DrawnTestCase> triggering = triggeringTestCases(attempt.replays());
		List<PromotionReplay> regressions = new ArrayList<>();
		List<PromotionReplay> references = new ArrayList<>();
		for (RegressionTestCase regression : regressionSet)
		{
			if (containsTestCase(triggering, regression.testCase()))
				continue;
			regressions.add(new PromotionReplay(PromotionReplay.Role.REGRESSION, regression.testCase(), null, null));
			if (regression.working() == null)
			{
				references.add(new PromotionReplay(PromotionReplay.Role.WORKING_REFERENCE, regression.testCase(), null,
					null));
			}
		}
		regressions.addAll(references);
		return regressions;
	}

	/**
	 * Starts a reduction's triggering streaks at two replays, resets regression replays while retaining working
	 * references, and drops paired replays, which each reduction plans again for itself.
	 *
	 * @param acceptedReplays the preceding attempt's replays
	 * @return the unassigned triggering and regression replays, followed by retained working reference replays
	 * @throws NullPointerException if {@code acceptedReplays} or an element is null
	 */
	public static List<PromotionReplay> resetReplays(List<PromotionReplay> acceptedReplays)
	{
		requireThat(acceptedReplays, "acceptedReplays").isNotNull();

		List<PromotionReplay> replays = new ArrayList<>();
		for (DrawnTestCase testCase : triggeringTestCases(acceptedReplays))
		{
			for (int replay = 0; replay < STARTING_TRIGGERING_REPLAYS; ++replay)
				replays.add(new PromotionReplay(PromotionReplay.Role.TRIGGERING, testCase, null, null));
		}
		for (PromotionReplay replay : acceptedReplays)
		{
			switch (replay.role())
			{
				case REGRESSION -> replays.add(new PromotionReplay(replay.role(), replay.testCase(), null, null));
				case WORKING_REFERENCE -> replays.add(replay);
				case TRIGGERING, PAIRED_REGRESSION, PAIRED_WORKING ->
				{
				}
			}
		}
		return List.copyOf(replays);
	}

	/**
	 * Returns the decision on an attempt of a campaign, keeping the recorded decision of a retained original attempt.
	 *
	 * @param attempt  the prompt author attempt
	 * @param campaign the campaign whose working candidate, regression set and retained results apply
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 */
	public static Decision decide(PromptAuthorAttempt attempt, Campaign campaign)
	{
		requireThat(campaign, "campaign").isNotNull();
		return decide(attempt, campaign.development().working().snapshot(), campaign.lineage().regressionSet(),
			campaign.development().testCaseOutcomes(), campaign.optimization().attempts());
	}

	/**
	 * Returns the decision on an attempt: the recorded decision of an original attempt whose result is retained, or
	 * else the decision of the adopted replays. A retained result keeps the decision that was made when it was retained,
	 * including one made under an earlier promotion rule.
	 *
	 * @param attempt         the prompt author attempt
	 * @param working         the working candidate's snapshot
	 * @param regressionSet   the lineage's regression set
	 * @param workingOutcomes the working candidate's adopted outcomes, aggregated by test case
	 * @param retained        the retained attempt results
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 */
	public static Decision decide(PromptAuthorAttempt attempt, Sha256 working, List<RegressionTestCase> regressionSet,
		List<TestCaseOutcomes> workingOutcomes, List<AttemptResult> retained)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(retained, "retained").isNotNull();
		if (attempt.minimality().testing() == null && attempt.minimality().acceptedReplays().isEmpty())
		{
			for (AttemptResult result : retained)
			{
				if (result.number() != attempt.number())
					continue;
				if (result.accepted())
					return Decision.PROMOTE;
				return Decision.REJECT;
			}
		}
		return decide(attempt, working, regressionSet, workingOutcomes);
	}

	/**
	 * Decides promotion from the adopted replays. An original attempt must fix at least one triggering test case; a
	 * reduction must retain every fix from its minimality search's last accepted replays. Either is rejected as soon as
	 * a regression group regresses, and waits for all needed outcomes unless rejection is certain. A reduction equal to
	 * the working snapshot is rejected without further checks because it preserves no edit.
	 *
	 * @param attempt         the prompt author attempt
	 * @param working         the working candidate's snapshot
	 * @param regressionSet   the lineage's regression set
	 * @param workingOutcomes the working candidate's adopted outcomes, aggregated by test case
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 */
	public static Decision decide(PromptAuthorAttempt attempt, Sha256 working, List<RegressionTestCase> regressionSet,
		List<TestCaseOutcomes> workingOutcomes)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(working, "working").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();

		boolean restoresWorking = attempt.minimality().testing() != null && attempt.snapshot().equals(working);
		if (restoresWorking)
			return Decision.REJECT;

		List<DrawnTestCase> triggering = triggeringTestCases(attempt.replays());
		if (attempt.minimality().testing() != null)
		{
			List<PromotionReplay> accepted = attempt.minimality().acceptedReplays();
			for (DrawnTestCase testCase : triggeringTestCases(accepted))
			{
				if (triggeringVerdict(testCase, accepted, workingOutcomes) == Verdict.PASS &&
					triggeringVerdict(testCase, attempt.replays(), workingOutcomes) == Verdict.FAIL)
				{
					return Decision.REJECT;
				}
			}
		}

		boolean possibleFix = false;
		for (DrawnTestCase testCase : triggering)
		{
			if (triggeringVerdict(testCase, attempt.replays(), workingOutcomes) != Verdict.FAIL)
				possibleFix = true;
		}
		if (!possibleFix)
			return Decision.REJECT;

		boolean pending = false;
		for (DrawnTestCase testCase : triggering)
		{
			if (triggeringVerdict(testCase, attempt.replays(), workingOutcomes) == null)
				pending = true;
		}
		for (PromotionReplay replay : attempt.replays())
		{
			if (replay.role() == PromotionReplay.Role.TRIGGERING && replay.verdict() == null)
				pending = true;
		}
		if (isRegressionUnplanned(attempt) && !regressionReplays(attempt, regressionSet).isEmpty())
			pending = true;
		for (String testId : regressionGroups(attempt.replays()))
		{
			switch (checkGroup(testId, attempt, regressionSet))
			{
				case REGRESSES ->
				{
					return Decision.REJECT;
				}
				case WAITS, NEEDS_PAIRED_REPLAYS -> pending = true;
				case PASSES ->
				{
				}
			}
		}
		if (pending)
			return Decision.PENDING;
		return Decision.PROMOTE;
	}

	/**
	 * Returns the failing replays that a prompt author attempt under validation can absorb before the next one rejects
	 * it, which wastes the replays still in flight.
	 * <p>
	 * A failing replay of a triggering test case ends that test case's fix, and the attempt is rejected once no
	 * triggering test case can be fixed, so the triggering replays absorb one failure less than the test cases they can
	 * still fix. A minimality reduction is rejected by the first failure of a test case that its accepted replays fixed,
	 * so it absorbs none. A failing regression replay rejects the attempt once its prompt test's failures are unlikely
	 * for the working candidate; the regression replays absorb the failures that keep every prompt test below that bar,
	 * with every pending replay of the working candidate assumed to pass. Failing replays of the working candidate decide
	 * nothing and consume none.
	 *
	 * @param attempt the prompt author attempt
	 * @param regressionSet the current lineage's regression set
	 * @param workingOutcomes the working candidate's adopted outcomes grouped by test case
	 * @return the spare failures, at most {@value Campaign#MAXIMUM_CEILING}
	 * @throws NullPointerException if any argument is null
	 */
	static int spareFailures(PromptAuthorAttempt attempt, List<RegressionTestCase> regressionSet,
		List<TestCaseOutcomes> workingOutcomes)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();
		if (attempt.minimality().testing() != null)
			return 0;
		int fixable = 0;
		for (DrawnTestCase testCase : triggeringTestCases(attempt.replays()))
		{
			if (triggeringVerdict(testCase, attempt.replays(), workingOutcomes) != Verdict.FAIL)
				++fixable;
		}
		int spare = Math.min(Campaign.MAXIMUM_CEILING, Math.max(0, fixable - 1));
		for (String testId : regressionGroups(attempt.replays()))
			spare = Math.min(spare, regressionSpareFailures(testId, attempt, regressionSet));
		return spare;
	}

	/**
	 * Returns the further failing replays that one prompt test's regression replays absorb before its failures are
	 * unlikely for the working candidate.
	 *
	 * @param testId the prompt test's identifier
	 * @param attempt the prompt author attempt
	 * @param regressionSet the current lineage's regression set
	 * @return the spare failures, at most {@value Campaign#MAXIMUM_CEILING}
	 */
	private static int regressionSpareFailures(String testId, PromptAuthorAttempt attempt,
		List<RegressionTestCase> regressionSet)
	{
		int replays = 0;
		int failures = 0;
		int pending = 0;
		List<DrawnTestCase> testCases = new ArrayList<>();
		int workingPasses = 0;
		int workingOutcomes = 0;
		for (PromotionReplay replay : attempt.replays())
		{
			if (!replay.testCase().testId().equals(testId))
				continue;
			switch (replay.role())
			{
				case REGRESSION, PAIRED_REGRESSION ->
				{
					++replays;
					if (replay.verdict() == null)
						++pending;
					else if (replay.verdict() == Verdict.FAIL)
						++failures;
					if (replay.role() == PromotionReplay.Role.REGRESSION && !containsTestCase(testCases, replay.testCase()))
						testCases.add(replay.testCase());
				}
				case PAIRED_WORKING ->
				{
					++workingOutcomes;
					if (replay.verdict() != Verdict.FAIL)
						++workingPasses;
				}
				case TRIGGERING, WORKING_REFERENCE ->
				{
				}
			}
		}
		for (DrawnTestCase testCase : testCases)
		{
			++workingOutcomes;
			if (workingVerdict(testCase, attempt, regressionSet) != Verdict.FAIL)
				++workingPasses;
		}
		for (int further = 1; further <= Math.min(pending, Campaign.MAXIMUM_CEILING); ++further)
		{
			if (isRegression(failures + further, replays, workingPasses, workingOutcomes))
				return further - 1;
		}
		return Campaign.MAXIMUM_CEILING;
	}

	/**
	 * The state of one prompt test's regression replays.
	 */
	private enum GroupCheck
	{
		/**
		 * No replay failed, or the group had all its paired replays without regressing.
		 */
		PASSES,
		/**
		 * The failures are unlikely for the working candidate at the latest check.
		 */
		REGRESSES,
		/**
		 * A replay that the next check needs has no outcome.
		 */
		WAITS,
		/**
		 * The latest check neither passed nor regressed, and the next check needs paired replays that are not planned.
		 */
		NEEDS_PAIRED_REPLAYS
	}

	/**
	 * Returns the prompt tests of the attempt's regression replays, in replay order.
	 *
	 * @param replays the attempt's replays
	 * @return the distinct test identifiers
	 */
	private static List<String> regressionGroups(List<PromotionReplay> replays)
	{
		List<String> testIds = new ArrayList<>();
		for (PromotionReplay replay : replays)
		{
			if (replay.role() == PromotionReplay.Role.REGRESSION && !testIds.contains(replay.testCase().testId()))
				testIds.add(replay.testCase().testId());
		}
		return testIds;
	}

	/**
	 * Checks one prompt test's regression replays at the end of the first pass and after every {@link
	 * #PAIRED_REPLAY_STEP} paired replays, up to {@link #MAXIMUM_PAIRED_REPLAYS}. The first pass counts the attempt's
	 * regression replays and one outcome of the working candidate on each of their test cases; each paired replay adds
	 * one replay of each.
	 *
	 * @param testId the prompt test
	 * @param attempt the attempt
	 * @param regressionSet the lineage's regression set
	 * @return the group's state
	 */
	private static GroupCheck checkGroup(String testId, PromptAuthorAttempt attempt,
		List<RegressionTestCase> regressionSet)
	{
		List<PromotionReplay> pairedAttemptReplays = new ArrayList<>();
		List<PromotionReplay> pairedWorkingReplays = new ArrayList<>();
		List<DrawnTestCase> testCases = new ArrayList<>();
		int replays = 0;
		int failures = 0;
		for (PromotionReplay replay : attempt.replays())
		{
			if (!replay.testCase().testId().equals(testId))
				continue;
			switch (replay.role())
			{
				case REGRESSION ->
				{
					if (replay.verdict() == null)
						return GroupCheck.WAITS;
					++replays;
					if (replay.verdict() == Verdict.FAIL)
						++failures;
					if (!containsTestCase(testCases, replay.testCase()))
						testCases.add(replay.testCase());
				}
				case PAIRED_REGRESSION -> pairedAttemptReplays.add(replay);
				case PAIRED_WORKING -> pairedWorkingReplays.add(replay);
				case TRIGGERING, WORKING_REFERENCE ->
				{
				}
			}
		}
		if (failures == 0)
			return GroupCheck.PASSES;
		int workingPasses = 0;
		int workingOutcomes = 0;
		for (DrawnTestCase testCase : testCases)
		{
			Verdict verdict = workingVerdict(testCase, attempt, regressionSet);
			if (verdict == null)
				return GroupCheck.WAITS;
			++workingOutcomes;
			if (verdict == Verdict.PASS)
				++workingPasses;
		}
		for (int pairs = 0; ; pairs += PAIRED_REPLAY_STEP)
		{
			if (pairs > 0)
			{
				if (pairedAttemptReplays.size() < pairs || pairedWorkingReplays.size() < pairs)
					return GroupCheck.NEEDS_PAIRED_REPLAYS;
				for (int index = pairs - PAIRED_REPLAY_STEP; index < pairs; ++index)
				{
					Verdict attemptVerdict = pairedAttemptReplays.get(index).verdict();
					Verdict workingVerdict = pairedWorkingReplays.get(index).verdict();
					if (attemptVerdict == null || workingVerdict == null)
						return GroupCheck.WAITS;
					++replays;
					if (attemptVerdict == Verdict.FAIL)
						++failures;
					++workingOutcomes;
					if (workingVerdict == Verdict.PASS)
						++workingPasses;
				}
			}
			if (isRegression(failures, replays, workingPasses, workingOutcomes))
				return GroupCheck.REGRESSES;
			if (pairs == MAXIMUM_PAIRED_REPLAYS)
				return GroupCheck.PASSES;
		}
	}

	/**
	 * Returns the order in which a group's paired replays cycle through its test cases: the test cases whose regression
	 * replay failed, then the others, each in regression replay order.
	 *
	 * @param testId the prompt test
	 * @param replays the attempt's replays
	 * @return the distinct test cases
	 */
	private static List<DrawnTestCase> pairedReplayOrder(String testId, List<PromotionReplay> replays)
	{
		List<DrawnTestCase> failed = new ArrayList<>();
		List<DrawnTestCase> passed = new ArrayList<>();
		for (PromotionReplay replay : replays)
		{
			if (replay.role() != PromotionReplay.Role.REGRESSION || !replay.testCase().testId().equals(testId))
				continue;
			if (replay.verdict() == Verdict.FAIL)
			{
				if (!containsTestCase(failed, replay.testCase()))
					failed.add(replay.testCase());
			}
			else if (!containsTestCase(passed, replay.testCase()))
				passed.add(replay.testCase());
		}
		List<DrawnTestCase> order = new ArrayList<>(failed);
		for (DrawnTestCase testCase : passed)
		{
			if (!containsTestCase(order, testCase))
				order.add(testCase);
		}
		return order;
	}

	/**
	 * Indicates whether an edit's regression failures are unlikely for the working candidate: the probability of at
	 * least {@code failures} failures in {@code replays} replays, at the failure rate {@code 1 - p} where
	 * {@code p = (passes + 1) / (outcomes + 2)}, is below {@code 1 / REGRESSION_BAR}.
	 *
	 * @param failures the edit's failed replays
	 * @param replays the edit's judged replays
	 * @param passes the working candidate's passes
	 * @param outcomes the working candidate's outcomes
	 * @return {@code true} when {@code REGRESSION_BAR * sum(C(replays, k) * a^k * b^(replays - k)) < (outcomes +
	 *   2)^replays} over {@code k} from {@code failures} to {@code replays}, with {@code a = outcomes + 1 - passes} and
	 *   {@code b = passes + 1}, compared exactly
	 */
	private static boolean isRegression(int failures, int replays, int passes, int outcomes)
	{
		BigInteger failureWeight = BigInteger.valueOf(outcomes + 1 - passes);
		BigInteger passWeight = BigInteger.valueOf(passes + 1);
		BigInteger total = BigInteger.valueOf(outcomes + 2);
		BigInteger tail = BigInteger.ZERO;
		BigInteger combinations = BigInteger.ONE;
		for (int k = 0; k <= replays; ++k)
		{
			if (k > 0)
			{
				combinations = combinations.multiply(BigInteger.valueOf(replays - k + 1)).
					divide(BigInteger.valueOf(k));
			}
			if (k >= failures)
				tail = tail.add(combinations.multiply(failureWeight.pow(k)).multiply(passWeight.pow(replays - k)));
		}
		return tail.multiply(BigInteger.valueOf(REGRESSION_BAR)).compareTo(total.pow(replays)) < 0;
	}

	/**
	 * Returns each triggering test case once, in replay order.
	 *
	 * @param replays the promotion evidence
	 * @return the distinct triggering test cases
	 */
	private static List<DrawnTestCase> triggeringTestCases(List<PromotionReplay> replays)
	{
		List<DrawnTestCase> testCases = new ArrayList<>();
		for (PromotionReplay replay : replays)
		{
			if (replay.role() == PromotionReplay.Role.TRIGGERING && !containsTestCase(testCases, replay.testCase()))
				testCases.add(replay.testCase());
		}
		return testCases;
	}

	/**
	 * Returns the verdict of a test case's triggering replays: whether they establish a fix or make one impossible.
	 *
	 * @param testCase the triggering test case, matched by content
	 * @param replays the tested attempt's replays
	 * @param workingOutcomes the working candidate's records by test case
	 * @return {@code PASS} for an unlikely passing streak, {@code FAIL} for a failed replay or fewer than two planned
	 *   replays, or {@code null} while a replay or extension is needed
	 */
	private static Verdict triggeringVerdict(DrawnTestCase testCase, List<PromotionReplay> replays,
		List<TestCaseOutcomes> workingOutcomes)
	{
		int passes = 0;
		boolean pending = false;
		for (PromotionReplay replay : replays)
		{
			if (replay.role() != PromotionReplay.Role.TRIGGERING || !isSameTestCase(testCase, replay.testCase()))
				continue;
			if (replay.verdict() == Verdict.FAIL)
				return Verdict.FAIL;
			if (replay.verdict() == Verdict.PASS)
				++passes;
			else
				pending = true;
		}
		if (pending)
			return null;
		if (passes < STARTING_TRIGGERING_REPLAYS)
			return Verdict.FAIL;
		if (isUnlikely(testCase, passes, workingOutcomes))
			return Verdict.PASS;
		return null;
	}

	/**
	 * Indicates whether a passing streak has probability below 10% under the working candidate's smoothed pass rate.
	 *
	 * @param testCase the triggering test case
	 * @param streak the attempt's passing streak length
	 * @param workingOutcomes the working candidate's adopted records
	 * @return {@code true} when {@code (passes + 1)^streak * 10 < (outcomes + 2)^streak}, compared exactly
	 */
	private static boolean isUnlikely(DrawnTestCase testCase, int streak, List<TestCaseOutcomes> workingOutcomes)
	{
		BigInteger passes = BigInteger.ONE;
		BigInteger outcomes = BigInteger.TWO;
		for (TestCaseOutcomes record : workingOutcomes)
		{
			if (isSameTestCase(testCase, record.testCase()))
			{
				passes = passes.add(BigInteger.valueOf(record.passes()));
				outcomes = outcomes.add(BigInteger.valueOf(record.outcomes()));
			}
		}
		return passes.pow(streak).multiply(BigInteger.TEN).compareTo(outcomes.pow(streak)) < 0;
	}

	/**
	 * Returns the working candidate's verdict on a test case: the regression set's verdict, or else the verdict of
	 * the attempt's reference replay.
	 *
	 * @param testCase the test case
	 * @param attempt the prompt author attempt
	 * @param regressionSet the lineage's regression set
	 * @return the verdict, or {@code null} if it is unknown
	 */
	private static Verdict workingVerdict(DrawnTestCase testCase, PromptAuthorAttempt attempt,
		List<RegressionTestCase> regressionSet)
	{
		for (RegressionTestCase regression : regressionSet)
		{
			if (regression.working() != null && isSameTestCase(regression.testCase(), testCase))
				return regression.working();
		}
		for (PromotionReplay replay : attempt.replays())
		{
			if (replay.role() == PromotionReplay.Role.WORKING_REFERENCE && replay.verdict() != null &&
				isSameTestCase(replay.testCase(), testCase))
			{
				return replay.verdict();
			}
		}
		return null;
	}

	/**
	 * Indicates whether a list holds the same test case.
	 *
	 * @param testCases the test cases
	 * @param testCase the test case to look for
	 * @return {@code true} if an element is the same test case
	 */
	static boolean containsTestCase(List<DrawnTestCase> testCases, DrawnTestCase testCase)
	{
		for (DrawnTestCase element : testCases)
		{
			if (isSameTestCase(element, testCase))
				return true;
		}
		return false;
	}

	/**
	 * Returns the snapshot that a promotion replay tests.
	 *
	 * @param attempt the prompt author attempt
	 * @param replay one of its replays
	 * @param working the working candidate's snapshot
	 * @return {@code working} for a replay that measures the working candidate, otherwise the prompt author attempt's
	 *   snapshot
	 */
	static Sha256 snapshot(PromptAuthorAttempt attempt, PromotionReplay replay, Sha256 working)
	{
		if (replay.role().measuresWorking())
			return working;
		return attempt.snapshot();
	}

	/**
	 * Indicates whether two drawn test cases are the same test case, regardless of how they were drawn or marked.
	 *
	 * @param first a drawn test case
	 * @param second another drawn test case
	 * @return {@code true} if both have the same cell, prompt test, test case and turns
	 */
	static boolean isSameTestCase(DrawnTestCase first, DrawnTestCase second)
	{
		return first.cellId().equals(second.cellId()) && first.testId().equals(second.testId()) &&
			first.testCase().equals(second.testCase()) && first.turns().equals(second.turns());
	}
}
