package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans trial runs and decides whether a proposed edit replaces the working candidate.
 * <p>
 * A triggering instance is a failure in the edited cell, the requirement category under a runner profile. The edit
 * runs each triggering instance twice and each other instance in the regression set once. The working candidate also
 * runs each of those other instances on which it has no outcome. The edit replaces the working candidate when every
 * triggering run passes and it fails no regression instance that the working candidate passes. These runs reuse fixed
 * instances and never count as representative evidence.
 */
public final class Promotion
{
	/**
	 * Require this many passing runs of each triggering instance before promoting an edit.
	 */
	public static final int TRIGGERING_RUNS = 2;

	/**
	 * The state of an author attempt's promotion.
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
	 * Plans the promotion runs of an author attempt.
	 *
	 * @param category the round cell that the attempt addresses
	 * @param failures the failing instances that the author received; only those in {@code category} trigger runs
	 * @param regressionSet the lineage's regression set
	 * @param generator the contract's generator, which marks the instances targeted
	 * @return the unassigned runs: the triggering runs, then the regression runs, then the working candidate's
	 *   reference runs
	 * @throws NullPointerException if any argument is null or either list contains null
	 */
	public static List<PromotionRun> plan(String category, List<TrialInstance> failures,
		List<RegressionInstance> regressionSet, Generator generator)
	{
		return plan(List.of(category), failures, regressionSet, generator);
	}

	/**
	 * Plans promotion runs for all triggering cells of one addressed category.
	 *
	 * @param categories the triggering cells
	 * @param failures the received failures
	 * @param regressionSet the regression set
	 * @param generator the targeting generator
	 * @return the same triggering, regression and reference plan used for single-cell attempts
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if categories is empty
	 */
	public static List<PromotionRun> plan(List<String> categories, List<TrialInstance> failures,
		List<RegressionInstance> regressionSet, Generator generator)
	{
		requireThat(categories, "categories").isNotEmpty();
		requireThat(failures, "failures").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(generator, "generator").isNotNull();
		List<PromotionRun> triggering = new ArrayList<>();
		List<TrialInstance> triggeringInstances = new ArrayList<>();
		for (TrialInstance failure : failures)
		{
			if (!categories.contains(failure.cellId()))
				continue;
			TrialInstance instance = generator.markTargeted(failure);
			triggeringInstances.add(instance);
			for (int run = 0; run < TRIGGERING_RUNS; ++run)
				triggering.add(new PromotionRun(PromotionRun.Role.TRIGGERING, instance, null, null));
		}
		List<PromotionRun> regressions = new ArrayList<>();
		List<PromotionRun> references = new ArrayList<>();
		for (RegressionInstance regression : regressionSet)
		{
			if (containsContent(triggeringInstances, regression.instance()))
				continue;
			regressions.add(new PromotionRun(PromotionRun.Role.REGRESSION, regression.instance(), null, null));
			if (regression.working() == null)
			{
				references.add(new PromotionRun(PromotionRun.Role.WORKING_REFERENCE, regression.instance(), null,
					null));
			}
		}
		List<PromotionRun> runs = new ArrayList<>(triggering);
		runs.addAll(regressions);
		runs.addAll(references);
		return runs;
	}

	/**
	 * Decides an author attempt's promotion from the runs adopted so far.
	 *
	 * @param attempt the author attempt
	 * @param regressionSet the lineage's regression set
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 */
	public static Decision decide(AuthorAttempt attempt, List<RegressionInstance> regressionSet)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		boolean pending = false;
		for (PromotionRun run : attempt.runs())
		{
			switch (run.role())
			{
				case TRIGGERING ->
				{
					if (run.verdict() == Verdict.FAIL)
						return Decision.REJECT;
					if (run.verdict() == null)
						pending = true;
				}
				case REGRESSION ->
				{
					if (run.verdict() == null)
						pending = true;
					else if (run.verdict() == Verdict.FAIL)
					{
						Verdict working = workingVerdict(run.instance(), attempt, regressionSet);
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
	 * Returns the working candidate's verdict on an instance: the regression set's verdict, or else the verdict of
	 * the attempt's reference run.
	 *
	 * @param instance the instance
	 * @param attempt the author attempt
	 * @param regressionSet the lineage's regression set
	 * @return the verdict, or {@code null} if it is unknown
	 */
	private static Verdict workingVerdict(TrialInstance instance, AuthorAttempt attempt,
		List<RegressionInstance> regressionSet)
	{
		for (RegressionInstance regression : regressionSet)
		{
			if (regression.working() != null && isSameContent(regression.instance(), instance))
				return regression.working();
		}
		for (PromotionRun run : attempt.runs())
		{
			if (run.role() == PromotionRun.Role.WORKING_REFERENCE && run.verdict() != null &&
				isSameContent(run.instance(), instance))
			{
				return run.verdict();
			}
		}
		return null;
	}

	/**
	 * Indicates whether a list holds an instance with the same content.
	 *
	 * @param instances the instances
	 * @param instance the instance to look for
	 * @return {@code true} if an element presents the same trial
	 */
	static boolean containsContent(List<TrialInstance> instances, TrialInstance instance)
	{
		for (TrialInstance element : instances)
		{
			if (isSameContent(element, instance))
				return true;
		}
		return false;
	}

	/**
	 * Returns the snapshot that a promotion run tests.
	 *
	 * @param attempt the author attempt
	 * @param run one of its runs
	 * @param working the working candidate's snapshot
	 * @return {@code working} for a reference run, otherwise the author attempt's snapshot
	 */
	static Sha256 snapshot(AuthorAttempt attempt, PromotionRun run, Sha256 working)
	{
		if (run.role() == PromotionRun.Role.WORKING_REFERENCE)
			return working;
		return attempt.snapshot();
	}

	/**
	 * Indicates whether two instances present the same trial, regardless of how they were drawn or marked.
	 *
	 * @param first an instance
	 * @param second another instance
	 * @return {@code true} if both have the same cell, test, variation choices and turns
	 */
	static boolean isSameContent(TrialInstance first, TrialInstance second)
	{
		return first.cellId().equals(second.cellId()) && first.testId().equals(second.testId()) &&
			first.choices().equals(second.choices()) && first.turns().equals(second.turns());
	}
}
