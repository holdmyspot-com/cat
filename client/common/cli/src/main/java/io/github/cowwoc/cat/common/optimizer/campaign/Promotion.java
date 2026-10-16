package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.DrawnTestCase;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;
import io.github.cowwoc.cat.common.secrets.PromotionAccess;
import io.github.cowwoc.cat.common.secrets.PromotionSecrets;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans trial runs and decides whether a proposed edit replaces the working candidate.
 * <p>
 * A triggering test case is a failure in the edited cell, the prompt test under a runner profile. The edit
 * starts each triggering test case with two runs and runs each other test case in the regression set once. The working
 * candidate also runs each of those other test cases on which it has no outcome. An edit fixes a triggering test case
 * when every run passes and its passing streak has probability below 10% under the working candidate's smoothed
 * observed pass rate, {@code (passes + 1) / (outcomes + 2)} on the same test case.
 * Passing streaks extend one run at a time, up to four runs. The edit replaces the working candidate when all needed
 * outcomes are known, it fixes at least one triggering test case and it fails no regression test case that the working
 * candidate passes. A minimality reduction must preserve every triggering fix in the last accepted runs. These runs
 * reuse fixed test cases and never count as representative evidence.
 */
public final class Promotion
{
	static
	{
		PromotionSecrets.setAccess(new PromotionAccess()
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
	 * The maximum passing streak tested before a test case whose streak is still likely is considered unfixed.
	 */
	public static final int MAXIMUM_TRIGGERING_RUNS = 4;

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
	 * Plans the promotion runs of a prompt author attempt.
	 *
	 * @param cell the round cell that the attempt addresses
	 * @param failures the failing test cases that the prompt author received; only those in {@code cell} trigger runs
	 * @param regressionSet the lineage's regression set
	 * @param generator the contract's generator, which marks the test cases targeted
	 * @return the unassigned runs: the triggering runs, then the regression runs, then the working candidate's
	 *   reference runs
	 * @throws NullPointerException if any argument is null or either list contains null
	 */
	public static List<PromotionRun> plan(String cell, List<DrawnTestCase> failures,
		List<RegressionTestCase> regressionSet, Generator generator)
	{
		return plan(List.of(cell), failures, regressionSet, generator);
	}

	/**
	 * Plans promotion runs for all triggering cells of one addressed test.
	 *
	 * @param cells the triggering cells
	 * @param failures the received failures
	 * @param regressionSet the regression set
	 * @param generator the targeting generator
	 * @return the same triggering, regression and reference plan used for single-cell attempts
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code cells} is empty
	 */
	public static List<PromotionRun> plan(List<String> cells, List<DrawnTestCase> failures,
		List<RegressionTestCase> regressionSet, Generator generator)
	{
		requireThat(cells, "cells").isNotEmpty();
		requireThat(failures, "failures").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
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
		List<PromotionRun> regressions = new ArrayList<>();
		List<PromotionRun> references = new ArrayList<>();
		for (RegressionTestCase regression : regressionSet)
		{
			if (containsTestCase(triggeringTestCases, regression.testCase()))
				continue;
			regressions.add(new PromotionRun(PromotionRun.Role.REGRESSION, regression.testCase(), null, null));
			if (regression.working() == null)
			{
				references.add(new PromotionRun(PromotionRun.Role.WORKING_REFERENCE, regression.testCase(), null,
					null));
			}
		}
		List<PromotionRun> runs = new ArrayList<>(triggering);
		runs.addAll(regressions);
		runs.addAll(references);
		return runs;
	}

	/**
	 * Plans one extra run for each fully passing triggering streak that is still likely and below the maximum.
	 *
	 * @param attempt the attempt whose adopted outcomes determine extensions
	 * @param workingOutcomes the working candidate's adopted outcomes, aggregated by test case
	 * @return the attempt with unassigned extensions appended, or the same attempt if none is due
	 * @throws NullPointerException if an argument or outcome is null
	 */
	public static PromptAuthorAttempt extend(PromptAuthorAttempt attempt, List<TestCaseOutcomes> workingOutcomes)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();

		List<PromotionRun> runs = new ArrayList<>(attempt.runs());
		for (DrawnTestCase testCase : triggeringTestCases(attempt.runs()))
		{
			List<PromotionRun> streak = attempt.runs().stream().filter(run ->
				run.role() == PromotionRun.Role.TRIGGERING && isSameTestCase(testCase, run.testCase())).toList();
			if (streak.size() >= STARTING_TRIGGERING_RUNS && streak.size() < MAXIMUM_TRIGGERING_RUNS &&
				streak.stream().allMatch(run -> run.verdict() == Verdict.PASS) &&
				!isUnlikely(testCase, streak.size(), workingOutcomes))
			{
				runs.add(new PromotionRun(PromotionRun.Role.TRIGGERING, testCase, null, null));
			}
		}
		if (runs.size() == attempt.runs().size())
			return attempt;
		return new PromptAuthorAttempt(attempt.number(), attempt.cell(), attempt.snapshot(), runs,
			attempt.minimality());
	}

	/**
	 * Starts a reduction's triggering streaks at two runs and resets regression runs while retaining working references.
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
				case TRIGGERING ->
				{
				}
			}
		}
		return List.copyOf(runs);
	}

	/**
	 * Decides promotion from the adopted runs. An original attempt must fix at least one triggering test case; a
	 * reduction must retain every fix from its minimality search's last accepted runs. Either rejects a regression
	 * where the working candidate passes, and waits for all needed outcomes unless rejection is certain.
	 *
	 * @param attempt the prompt author attempt
	 * @param regressionSet the lineage's regression set
	 * @param workingOutcomes the working candidate's adopted outcomes, aggregated by test case
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 */
	public static Decision decide(PromptAuthorAttempt attempt, List<RegressionTestCase> regressionSet,
		List<TestCaseOutcomes> workingOutcomes)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();

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
			switch (run.role())
			{
				case TRIGGERING ->
				{
					if (run.verdict() == null)
						pending = true;
				}
				case REGRESSION ->
				{
					if (run.verdict() == null)
						pending = true;
					else if (run.verdict() == Verdict.FAIL)
					{
						Verdict working = workingVerdict(run.testCase(), attempt, regressionSet);
						if (working == Verdict.PASS)
							return Decision.REJECT;
						if (working == null)
							pending = true;
					}
				}
				// A reference run matters only through the regression run that it explains
				case WORKING_REFERENCE ->
				{
				}
			}
		}
		if (pending)
			return Decision.PENDING;
		return Decision.PROMOTE;
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
	 * @return {@code PASS} for an unlikely passing streak, {@code FAIL} for a failed run, fewer than two planned runs,
	 *   or a still-likely streak at four passes, or {@code null} while a run or extension is needed
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
		if (passes < MAXIMUM_TRIGGERING_RUNS)
			return null;
		return Verdict.FAIL;
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
	 * @return {@code working} for a reference run, otherwise the prompt author attempt's snapshot
	 */
	static Sha256 snapshot(PromptAuthorAttempt attempt, PromotionRun run, Sha256 working)
	{
		if (run.role() == PromotionRun.Role.WORKING_REFERENCE)
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
