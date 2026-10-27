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
 * Plans trial runs and decides whether a proposed edit replaces the working candidate.
 * <p>
 * A triggering test case is a failure in the edited cell, the prompt test under a runner profile. The edit
 * starts each triggering test case with two runs. An edit fixes a triggering test case when every run passes and its
 * passing streak has probability below 10% under the working candidate's smoothed observed pass rate,
 * {@code (passes + 1) / (outcomes + 2)} on the same test case. A passing streak extends one run at a time until it is
 * unlikely, so the more often the working candidate passes a test case, the more runs a fix needs there; the smoothed
 * rate is below 1, so every streak that keeps passing ends in a fix.
 * <p>
 * Once some triggering test case has two passes and no failure, or at once for a minimality reduction, the edit runs
 * each other test case in the regression set once, and the working candidate runs each of those test cases on which it
 * has no outcome. The regression runs of each prompt test form a group. A group passes when none of its runs fails;
 * it regresses when its failures would occur with probability below 5%, {@code 1 / REGRESSION_BAR}, at the working
 * candidate's smoothed pass rate on the group's test cases. Otherwise the edit and the working candidate run
 * {@link #RERUN_STEP} paired reruns of the group's test cases, and the group is judged again, until it regresses or
 * has had {@link #MAXIMUM_RERUNS} paired reruns and passes.
 * <p>
 * The edit replaces the working candidate when it fixes at least one triggering test case and every group passes; it
 * is rejected as soon as every fix is impossible or a group regresses. A minimality reduction must preserve every
 * triggering fix in the last accepted runs. These runs reuse fixed test cases and never count as representative
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
	 * The initial number of promotion runs planned for each distinct triggering test case.
	 */
	public static final int STARTING_TRIGGERING_RUNS = 2;
	/**
	 * The probability below which a group's regression failures show that the edit is worse than the working
	 * candidate, as a denominator: the bar is {@code 1 / 20}, or 5%. Simulated at a 93% working pass rate, the
	 * regression check with this bar, {@link #RERUN_STEP} and {@link #MAXIMUM_RERUNS} rejects an equally good edit about
	 * 2.4% of the time per prompt test and an edit that passes 80% about 38% of the time.
	 */
	private static final int REGRESSION_BAR = 20;
	/**
	 * The number of paired reruns that a group receives before it is judged again. Simulated at a 93% working pass
	 * rate, the regression check with this step, {@link #REGRESSION_BAR} and {@link #MAXIMUM_RERUNS} rejects an
	 * equally good edit about 2.4% of the time per prompt test and an edit that passes 80% about 38% of the time.
	 */
	public static final int RERUN_STEP = 5;
	/**
	 * The number of paired reruns after which a group that has not regressed passes. Simulated at a 93% working pass
	 * rate, the regression check with this limit, {@link #REGRESSION_BAR} and {@link #RERUN_STEP} rejects an equally
	 * good edit about 2.4% of the time per prompt test and an edit that passes 80% about 38% of the time.
	 */
	public static final int MAXIMUM_RERUNS = 15;

	/**
	 * The state of a prompt author attempt's promotion.
	 */
	public enum Decision
	{
		/**
		 * Some run that the decision needs has no outcome yet.
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
	 * Plans the triggering runs of a prompt author attempt for all triggering cells of one addressed test. The
	 * regression runs follow once {@link #extend} finds them due.
	 *
	 * @param cells the triggering cells
	 * @param failures the failing test cases that the prompt author received; only those in {@code cells} trigger runs
	 * @param generator the contract's generator, which marks the test cases targeted
	 * @return the unassigned runs, {@link #STARTING_TRIGGERING_RUNS} for each distinct triggering test case
	 * @throws NullPointerException if any argument is null or {@code failures} contains null
	 * @throws IllegalArgumentException if {@code cells} is empty
	 */
	public static List<PromotionRun> plan(List<String> cells, List<DrawnTestCase> failures, Generator generator)
	{
		requireThat(cells, "cells").isNotEmpty();
		requireThat(failures, "failures").isNotNull();
		requireThat(generator, "generator").isNotNull();
		List<PromotionRun> triggering = new ArrayList<>();
		List<DrawnTestCase> triggeringTestCases = new ArrayList<>();
		for (DrawnTestCase failure : failures)
		{
			if (!cells.contains(failure.cellId()))
				continue;
			if (containsTestCase(triggeringTestCases, failure))
				continue;
			DrawnTestCase testCase = generator.markTargeted(failure);
			triggeringTestCases.add(testCase);
			for (int run = 0; run < STARTING_TRIGGERING_RUNS; ++run)
				triggering.add(new PromotionRun(PromotionRun.Role.TRIGGERING, testCase, null, null));
		}
		return triggering;
	}

	/**
	 * Plans the runs that the attempt's adopted outcomes make due: one extra run for each fully passing triggering
	 * streak that is still likely; the regression runs and the working candidate's missing reference runs once they
	 * are due and not yet planned; and {@link #RERUN_STEP} paired reruns for each group whose last check neither passed
	 * nor regressed. A group's reruns cycle through its test cases in regression run order, those the attempt failed
	 * first, and each rerun of the attempt is followed by the working candidate's rerun of the same test case.
	 *
	 * @param attempt the attempt whose adopted outcomes determine extensions
	 * @param regressionSet the lineage's regression set
	 * @param workingOutcomes the working candidate's adopted outcomes, aggregated by test case
	 * @param sharedReferences the test cases on which another run already measures the working candidate, so that the
	 *   attempt plans no reference run of its own there
	 * @return the attempt with unassigned runs appended, or the same attempt if none is due
	 * @throws NullPointerException if an argument or element is null
	 */
	public static PromptAuthorAttempt extend(PromptAuthorAttempt attempt, List<RegressionTestCase> regressionSet,
		List<TestCaseOutcomes> workingOutcomes, List<DrawnTestCase> sharedReferences)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();
		requireThat(sharedReferences, "sharedReferences").isNotNull();

		List<PromotionRun> runs = new ArrayList<>(attempt.runs());
		for (DrawnTestCase testCase : triggeringTestCases(attempt.runs()))
		{
			List<PromotionRun> streak = attempt.runs().stream().filter(run ->
				run.role() == PromotionRun.Role.TRIGGERING && isSameTestCase(testCase, run.testCase())).toList();
			if (streak.size() >= STARTING_TRIGGERING_RUNS && streak.stream().allMatch(run -> run.verdict() == Verdict.PASS) &&
				!isUnlikely(testCase, streak.size(), workingOutcomes))
			{
				runs.add(new PromotionRun(PromotionRun.Role.TRIGGERING, testCase, null, null));
			}
		}
		if (isRegressionUnplanned(attempt))
		{
			for (PromotionRun run : regressionRuns(attempt, regressionSet))
			{
				if (run.role() == PromotionRun.Role.REGRESSION || !containsTestCase(sharedReferences, run.testCase()))
					runs.add(run);
			}
		}
		for (String testId : regressionGroups(attempt.runs()))
		{
			if (checkGroup(testId, attempt, regressionSet) != GroupCheck.NEEDS_RERUNS)
				continue;
			List<DrawnTestCase> order = rerunOrder(testId, attempt.runs());
			long planned = attempt.runs().stream().filter(run -> run.role() == PromotionRun.Role.REGRESSION_RERUN &&
				run.testCase().testId().equals(testId)).count();
			for (long rerun = planned; rerun < planned + RERUN_STEP; ++rerun)
			{
				DrawnTestCase testCase = order.get((int) (rerun % order.size()));
				runs.add(new PromotionRun(PromotionRun.Role.REGRESSION_RERUN, testCase, null, null));
				runs.add(new PromotionRun(PromotionRun.Role.WORKING_RERUN, testCase, null, null));
			}
		}
		if (runs.size() == attempt.runs().size())
			return attempt;
		return new PromptAuthorAttempt(attempt.number(), attempt.cell(), attempt.snapshot(), runs,
			attempt.minimality());
	}

	/**
	 * Indicates whether the attempt's regression runs are due but not planned. They are due for a minimality
	 * reduction, and for an original attempt once some triggering test case has {@link #STARTING_TRIGGERING_RUNS}
	 * passes and no failure.
	 *
	 * @param attempt the attempt
	 * @return {@code true} if the attempt has no regression run and its regression runs are due
	 */
	private static boolean isRegressionUnplanned(PromptAuthorAttempt attempt)
	{
		if (attempt.runs().stream().anyMatch(run -> run.role() == PromotionRun.Role.REGRESSION))
			return false;
		if (attempt.minimality().testing() != null)
			return true;
		for (DrawnTestCase testCase : triggeringTestCases(attempt.runs()))
		{
			int passes = 0;
			boolean failed = false;
			for (PromotionRun run : attempt.runs())
			{
				if (run.role() != PromotionRun.Role.TRIGGERING || !isSameTestCase(testCase, run.testCase()))
					continue;
				if (run.verdict() == Verdict.FAIL)
					failed = true;
				else if (run.verdict() == Verdict.PASS)
					++passes;
			}
			if (!failed && passes >= STARTING_TRIGGERING_RUNS)
				return true;
		}
		return false;
	}

	/**
	 * Returns the regression runs of an attempt: one run of each regression test case that does not trigger the
	 * attempt, then one reference run of the working candidate on each such test case on which it has no outcome.
	 *
	 * @param attempt the attempt
	 * @param regressionSet the lineage's regression set
	 * @return the unassigned runs
	 */
	private static List<PromotionRun> regressionRuns(PromptAuthorAttempt attempt,
		List<RegressionTestCase> regressionSet)
	{
		List<DrawnTestCase> triggering = triggeringTestCases(attempt.runs());
		List<PromotionRun> regressions = new ArrayList<>();
		List<PromotionRun> references = new ArrayList<>();
		for (RegressionTestCase regression : regressionSet)
		{
			if (containsTestCase(triggering, regression.testCase()))
				continue;
			regressions.add(new PromotionRun(PromotionRun.Role.REGRESSION, regression.testCase(), null, null));
			if (regression.working() == null)
			{
				references.add(new PromotionRun(PromotionRun.Role.WORKING_REFERENCE, regression.testCase(), null,
					null));
			}
		}
		regressions.addAll(references);
		return regressions;
	}

	/**
	 * Starts a reduction's triggering streaks at two runs, resets regression runs while retaining working references,
	 * and drops paired reruns, which each reduction plans again for itself.
	 *
	 * @param acceptedRuns the preceding attempt's runs
	 * @return the unassigned triggering and regression runs, followed by retained working reference runs
	 * @throws NullPointerException if {@code acceptedRuns} or an element is null
	 */
	public static List<PromotionRun> resetRuns(List<PromotionRun> acceptedRuns)
	{
		requireThat(acceptedRuns, "acceptedRuns").isNotNull();

		List<PromotionRun> runs = new ArrayList<>();
		for (DrawnTestCase testCase : triggeringTestCases(acceptedRuns))
		{
			for (int run = 0; run < STARTING_TRIGGERING_RUNS; ++run)
				runs.add(new PromotionRun(PromotionRun.Role.TRIGGERING, testCase, null, null));
		}
		for (PromotionRun run : acceptedRuns)
		{
			switch (run.role())
			{
				case REGRESSION -> runs.add(new PromotionRun(run.role(), run.testCase(), null, null));
				case WORKING_REFERENCE -> runs.add(run);
				case TRIGGERING, REGRESSION_RERUN, WORKING_RERUN ->
				{
				}
			}
		}
		return List.copyOf(runs);
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
	 * else the decision of the adopted runs. A retained result keeps the decision that was made when it was retained,
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
		if (attempt.minimality().testing() == null && attempt.minimality().acceptedRuns().isEmpty())
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
	 * Decides promotion from the adopted runs. An original attempt must fix at least one triggering test case; a
	 * reduction must retain every fix from its minimality search's last accepted runs. Either is rejected as soon as
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

		List<DrawnTestCase> triggering = triggeringTestCases(attempt.runs());
		if (attempt.minimality().testing() != null)
		{
			List<PromotionRun> accepted = attempt.minimality().acceptedRuns();
			for (DrawnTestCase testCase : triggeringTestCases(accepted))
			{
				if (triggeringVerdict(testCase, accepted, workingOutcomes) == Verdict.PASS &&
					triggeringVerdict(testCase, attempt.runs(), workingOutcomes) == Verdict.FAIL)
				{
					return Decision.REJECT;
				}
			}
		}

		boolean possibleFix = false;
		for (DrawnTestCase testCase : triggering)
		{
			if (triggeringVerdict(testCase, attempt.runs(), workingOutcomes) != Verdict.FAIL)
				possibleFix = true;
		}
		if (!possibleFix)
			return Decision.REJECT;

		boolean pending = false;
		for (DrawnTestCase testCase : triggering)
		{
			if (triggeringVerdict(testCase, attempt.runs(), workingOutcomes) == null)
				pending = true;
		}
		for (PromotionRun run : attempt.runs())
		{
			if (run.role() == PromotionRun.Role.TRIGGERING && run.verdict() == null)
				pending = true;
		}
		if (isRegressionUnplanned(attempt) && !regressionRuns(attempt, regressionSet).isEmpty())
			pending = true;
		for (String testId : regressionGroups(attempt.runs()))
		{
			switch (checkGroup(testId, attempt, regressionSet))
			{
				case REGRESSES ->
				{
					return Decision.REJECT;
				}
				case WAITS, NEEDS_RERUNS -> pending = true;
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
	 * The state of one prompt test's regression runs.
	 */
	private enum GroupCheck
	{
		/**
		 * No run failed, or the group had all its paired reruns without regressing.
		 */
		PASSES,
		/**
		 * The failures are unlikely for the working candidate at the latest check.
		 */
		REGRESSES,
		/**
		 * A run that the next check needs has no outcome.
		 */
		WAITS,
		/**
		 * The latest check neither passed nor regressed, and the next check needs reruns that are not planned.
		 */
		NEEDS_RERUNS
	}

	/**
	 * Returns the prompt tests of the attempt's regression runs, in run order.
	 *
	 * @param runs the attempt's runs
	 * @return the distinct test identifiers
	 */
	private static List<String> regressionGroups(List<PromotionRun> runs)
	{
		List<String> testIds = new ArrayList<>();
		for (PromotionRun run : runs)
		{
			if (run.role() == PromotionRun.Role.REGRESSION && !testIds.contains(run.testCase().testId()))
				testIds.add(run.testCase().testId());
		}
		return testIds;
	}

	/**
	 * Checks one prompt test's regression runs at the end of the first pass and after every {@link #RERUN_STEP}
	 * paired reruns, up to {@link #MAXIMUM_RERUNS}. The first pass counts the attempt's regression runs and one outcome
	 * of the working candidate on each of their test cases; each paired rerun adds one run of each.
	 *
	 * @param testId the prompt test
	 * @param attempt the attempt
	 * @param regressionSet the lineage's regression set
	 * @return the group's state
	 */
	private static GroupCheck checkGroup(String testId, PromptAuthorAttempt attempt,
		List<RegressionTestCase> regressionSet)
	{
		List<PromotionRun> attemptReruns = new ArrayList<>();
		List<PromotionRun> workingReruns = new ArrayList<>();
		List<DrawnTestCase> testCases = new ArrayList<>();
		int runs = 0;
		int failures = 0;
		for (PromotionRun run : attempt.runs())
		{
			if (!run.testCase().testId().equals(testId))
				continue;
			switch (run.role())
			{
				case REGRESSION ->
				{
					if (run.verdict() == null)
						return GroupCheck.WAITS;
					++runs;
					if (run.verdict() == Verdict.FAIL)
						++failures;
					if (!containsTestCase(testCases, run.testCase()))
						testCases.add(run.testCase());
				}
				case REGRESSION_RERUN -> attemptReruns.add(run);
				case WORKING_RERUN -> workingReruns.add(run);
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
		for (int reruns = 0; ; reruns += RERUN_STEP)
		{
			if (reruns > 0)
			{
				if (attemptReruns.size() < reruns || workingReruns.size() < reruns)
					return GroupCheck.NEEDS_RERUNS;
				for (int index = reruns - RERUN_STEP; index < reruns; ++index)
				{
					Verdict attemptVerdict = attemptReruns.get(index).verdict();
					Verdict workingVerdict = workingReruns.get(index).verdict();
					if (attemptVerdict == null || workingVerdict == null)
						return GroupCheck.WAITS;
					++runs;
					if (attemptVerdict == Verdict.FAIL)
						++failures;
					++workingOutcomes;
					if (workingVerdict == Verdict.PASS)
						++workingPasses;
				}
			}
			if (isRegression(failures, runs, workingPasses, workingOutcomes))
				return GroupCheck.REGRESSES;
			if (reruns == MAXIMUM_RERUNS)
				return GroupCheck.PASSES;
		}
	}

	/**
	 * Returns the order in which a group's reruns cycle through its test cases: the test cases whose regression run
	 * failed, then the others, each in regression run order.
	 *
	 * @param testId the prompt test
	 * @param runs the attempt's runs
	 * @return the distinct test cases
	 */
	private static List<DrawnTestCase> rerunOrder(String testId, List<PromotionRun> runs)
	{
		List<DrawnTestCase> failed = new ArrayList<>();
		List<DrawnTestCase> passed = new ArrayList<>();
		for (PromotionRun run : runs)
		{
			if (run.role() != PromotionRun.Role.REGRESSION || !run.testCase().testId().equals(testId))
				continue;
			if (run.verdict() == Verdict.FAIL)
			{
				if (!containsTestCase(failed, run.testCase()))
					failed.add(run.testCase());
			}
			else if (!containsTestCase(passed, run.testCase()))
				passed.add(run.testCase());
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
	 * least {@code failures} failures in {@code runs} runs, at the failure rate {@code 1 - p} where
	 * {@code p = (passes + 1) / (outcomes + 2)}, is below {@code 1 / REGRESSION_BAR}.
	 *
	 * @param failures the edit's failed runs
	 * @param runs the edit's judged runs
	 * @param passes the working candidate's passes
	 * @param outcomes the working candidate's outcomes
	 * @return {@code true} when {@code REGRESSION_BAR * sum(C(runs, k) * a^k * b^(runs - k)) < (outcomes + 2)^runs}
	 *   over {@code k} from {@code failures} to {@code runs}, with {@code a = outcomes + 1 - passes} and
	 *   {@code b = passes + 1}, compared exactly
	 */
	private static boolean isRegression(int failures, int runs, int passes, int outcomes)
	{
		BigInteger failureWeight = BigInteger.valueOf(outcomes + 1 - passes);
		BigInteger passWeight = BigInteger.valueOf(passes + 1);
		BigInteger total = BigInteger.valueOf(outcomes + 2);
		BigInteger tail = BigInteger.ZERO;
		BigInteger combinations = BigInteger.ONE;
		for (int k = 0; k <= runs; ++k)
		{
			if (k > 0)
			{
				combinations = combinations.multiply(BigInteger.valueOf(runs - k + 1)).
					divide(BigInteger.valueOf(k));
			}
			if (k >= failures)
				tail = tail.add(combinations.multiply(failureWeight.pow(k)).multiply(passWeight.pow(runs - k)));
		}
		return tail.multiply(BigInteger.valueOf(REGRESSION_BAR)).compareTo(total.pow(runs)) < 0;
	}

	/**
	 * Returns each triggering test case once, in run order.
	 *
	 * @param runs the promotion evidence
	 * @return the distinct triggering test cases
	 */
	private static List<DrawnTestCase> triggeringTestCases(List<PromotionRun> runs)
	{
		List<DrawnTestCase> testCases = new ArrayList<>();
		for (PromotionRun run : runs)
		{
			if (run.role() == PromotionRun.Role.TRIGGERING && !containsTestCase(testCases, run.testCase()))
				testCases.add(run.testCase());
		}
		return testCases;
	}

	/**
	 * Returns the verdict of a test case's triggering runs: whether they establish a fix or make one impossible.
	 *
	 * @param testCase the triggering test case, matched by content
	 * @param runs the tested attempt's runs
	 * @param workingOutcomes the working candidate's records by test case
	 * @return {@code PASS} for an unlikely passing streak, {@code FAIL} for a failed run or fewer than two planned runs,
	 *   or {@code null} while a run or extension is needed
	 */
	private static Verdict triggeringVerdict(DrawnTestCase testCase, List<PromotionRun> runs,
		List<TestCaseOutcomes> workingOutcomes)
	{
		int passes = 0;
		boolean pending = false;
		for (PromotionRun run : runs)
		{
			if (run.role() != PromotionRun.Role.TRIGGERING || !isSameTestCase(testCase, run.testCase()))
				continue;
			if (run.verdict() == Verdict.FAIL)
				return Verdict.FAIL;
			if (run.verdict() == Verdict.PASS)
				++passes;
			else
				pending = true;
		}
		if (pending)
			return null;
		if (passes < STARTING_TRIGGERING_RUNS)
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
	 * the attempt's reference run.
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
		for (PromotionRun run : attempt.runs())
		{
			if (run.role() == PromotionRun.Role.WORKING_REFERENCE && run.verdict() != null &&
				isSameTestCase(run.testCase(), testCase))
			{
				return run.verdict();
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
	 * Returns the snapshot that a promotion run tests.
	 *
	 * @param attempt the prompt author attempt
	 * @param run one of its runs
	 * @param working the working candidate's snapshot
	 * @return {@code working} for a run that measures the working candidate, otherwise the prompt author attempt's
	 *   snapshot
	 */
	static Sha256 snapshot(PromptAuthorAttempt attempt, PromotionRun run, Sha256 working)
	{
		if (run.role().measuresWorking())
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
