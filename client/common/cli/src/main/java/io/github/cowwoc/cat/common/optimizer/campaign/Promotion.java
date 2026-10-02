package io.github.cowwoc.cat.common.optimizer.campaign;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.contract.Generator;
import io.github.cowwoc.cat.common.optimizer.contract.TrialInstance;
import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans trial runs and decides whether a proposed edit replaces the working candidate.
 * <p>
 * A triggering instance is a failure in the edited cell, the requirement category under a runner profile. The edit
 * starts each triggering instance with two runs and runs each other instance in the regression set once. The working
 * candidate also runs each of those other instances on which it has no outcome. An edit fixes a triggering instance
 * when every run passes and its passing streak has probability below 10% under the working candidate's smoothed
 * observed pass rate, {@code (passes + 1) / (outcomes + 2)} on the same trial content.
 * Passing streaks extend one run at a time, up to four runs. The edit replaces the working candidate when all needed
 * outcomes are known, it fixes at least one triggering instance and it fails no regression instance that the working
 * candidate passes. A minimality reduction must preserve every triggering fix in the last accepted runs. These runs
 * reuse fixed instances and never count as representative evidence.
 */
public final class Promotion
{
	/**
	 * The initial number of promotion runs planned for each distinct triggering instance.
	 */
	public static final int STARTING_TRIGGERING_RUNS = 2;
	/**
	 * The maximum passing streak tested before an instance whose streak is still likely is considered unfixed.
	 */
	public static final int MAXIMUM_TRIGGERING_RUNS = 4;

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
	 * @param cell the round cell that the attempt addresses
	 * @param failures the failing instances that the author received; only those in {@code cell} trigger runs
	 * @param regressionSet the lineage's regression set
	 * @param generator the contract's generator, which marks the instances targeted
	 * @return the unassigned runs: the triggering runs, then the regression runs, then the working candidate's
	 *   reference runs
	 * @throws NullPointerException if any argument is null or either list contains null
	 */
	public static List<PromotionRun> plan(String cell, List<TrialInstance> failures,
		List<RegressionInstance> regressionSet, Generator generator)
	{
		return plan(List.of(cell), failures, regressionSet, generator);
	}

	/**
	 * Plans promotion runs for all triggering cells of one addressed category.
	 *
	 * @param cells the triggering cells
	 * @param failures the received failures
	 * @param regressionSet the regression set
	 * @param generator the targeting generator
	 * @return the same triggering, regression and reference plan used for single-cell attempts
	 * @throws NullPointerException if any argument is null
	 * @throws IllegalArgumentException if {@code cells} is empty
	 */
	public static List<PromotionRun> plan(List<String> cells, List<TrialInstance> failures,
		List<RegressionInstance> regressionSet, Generator generator)
	{
		requireThat(cells, "cells").isNotEmpty();
		requireThat(failures, "failures").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(generator, "generator").isNotNull();
		List<PromotionRun> triggering = new ArrayList<>();
		List<TrialInstance> triggeringInstances = new ArrayList<>();
		for (TrialInstance failure : failures)
		{
			if (!cells.contains(failure.cellId()))
				continue;
			if (containsContent(triggeringInstances, failure))
				continue;
			TrialInstance instance = generator.markTargeted(failure);
			triggeringInstances.add(instance);
			for (int run = 0; run < STARTING_TRIGGERING_RUNS; ++run)
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
	 * Plans one extra run for each fully passing triggering streak that is still likely and below the maximum.
	 *
	 * @param attempt the attempt whose adopted outcomes determine extensions
	 * @param workingOutcomes the working candidate's adopted outcomes, aggregated by trial content
	 * @return the attempt with unassigned extensions appended, or the same attempt if none is due
	 * @throws NullPointerException if an argument or outcome is null
	 */
	public static AuthorAttempt extend(AuthorAttempt attempt, List<InstanceOutcomes> workingOutcomes)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();

		List<PromotionRun> runs = new ArrayList<>(attempt.runs());
		for (TrialInstance instance : triggeringInstances(attempt.runs()))
		{
			List<PromotionRun> streak = attempt.runs().stream().filter(run ->
				run.role() == PromotionRun.Role.TRIGGERING && isSameContent(instance, run.instance())).toList();
			if (streak.size() >= STARTING_TRIGGERING_RUNS && streak.size() < MAXIMUM_TRIGGERING_RUNS &&
				streak.stream().allMatch(run -> run.verdict() == Verdict.PASS) &&
				!isUnlikely(instance, streak.size(), workingOutcomes))
			{
				runs.add(new PromotionRun(PromotionRun.Role.TRIGGERING, instance, null, null));
			}
		}
		if (runs.size() == attempt.runs().size())
			return attempt;
		return new AuthorAttempt(attempt.number(), attempt.cell(), attempt.snapshot(), runs, attempt.minimality());
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
		for (TrialInstance instance : triggeringInstances(acceptedRuns))
		{
			for (int run = 0; run < STARTING_TRIGGERING_RUNS; ++run)
				runs.add(new PromotionRun(PromotionRun.Role.TRIGGERING, instance, null, null));
		}
		for (PromotionRun run : acceptedRuns)
		{
			switch (run.role())
			{
				case REGRESSION -> runs.add(new PromotionRun(run.role(), run.instance(), null, null));
				case WORKING_REFERENCE -> runs.add(run);
				case TRIGGERING ->
				{
				}
			}
		}
		return List.copyOf(runs);
	}

	/**
	 * Decides promotion from the adopted runs. An original attempt must fix at least one triggering instance; a
	 * reduction must retain every fix from its minimality search's last accepted runs. Either rejects a regression
	 * where the working candidate passes, and waits for all needed outcomes unless rejection is certain.
	 *
	 * @param attempt the author attempt
	 * @param regressionSet the lineage's regression set
	 * @param workingOutcomes the working candidate's adopted outcomes, aggregated by trial content
	 * @return the decision
	 * @throws NullPointerException if any argument is null
	 */
	public static Decision decide(AuthorAttempt attempt, List<RegressionInstance> regressionSet,
		List<InstanceOutcomes> workingOutcomes)
	{
		requireThat(attempt, "attempt").isNotNull();
		requireThat(regressionSet, "regressionSet").isNotNull();
		requireThat(workingOutcomes, "workingOutcomes").isNotNull();

		List<TrialInstance> triggering = triggeringInstances(attempt.runs());
		if (attempt.minimality().testing() != null)
		{
			List<PromotionRun> accepted = attempt.minimality().acceptedRuns();
			for (TrialInstance instance : triggeringInstances(accepted))
			{
				if (triggeringVerdict(instance, accepted, workingOutcomes) == Verdict.PASS &&
					triggeringVerdict(instance, attempt.runs(), workingOutcomes) == Verdict.FAIL)
				{
					return Decision.REJECT;
				}
			}
		}

		boolean possibleFix = false;
		for (TrialInstance instance : triggering)
		{
			if (triggeringVerdict(instance, attempt.runs(), workingOutcomes) != Verdict.FAIL)
				possibleFix = true;
		}
		if (!possibleFix)
			return Decision.REJECT;

		boolean pending = false;
		for (TrialInstance instance : triggering)
		{
			if (triggeringVerdict(instance, attempt.runs(), workingOutcomes) == null)
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
	 * Returns triggering instances once per trial content, in run order.
	 *
	 * @param runs the promotion evidence
	 * @return the distinct triggering instances
	 */
	private static List<TrialInstance> triggeringInstances(List<PromotionRun> runs)
	{
		List<TrialInstance> instances = new ArrayList<>();
		for (PromotionRun run : runs)
		{
			if (run.role() == PromotionRun.Role.TRIGGERING && !containsContent(instances, run.instance()))
				instances.add(run.instance());
		}
		return instances;
	}

	/**
	 * Returns whether an instance's triggering runs establish a fix or make one impossible.
	 *
	 * @param instance the triggering instance, matched by content
	 * @param runs the tested attempt's runs
	 * @param workingOutcomes the working candidate's records on trial content
	 * @return {@code PASS} for an unlikely passing streak, {@code FAIL} for a failed run, fewer than two planned runs,
	 *   or a still-likely streak at four passes, or {@code null} while a run or extension is needed
	 */
	private static Verdict triggeringVerdict(TrialInstance instance, List<PromotionRun> runs,
		List<InstanceOutcomes> workingOutcomes)
	{
		int passes = 0;
		boolean pending = false;
		for (PromotionRun run : runs)
		{
			if (run.role() != PromotionRun.Role.TRIGGERING || !isSameContent(instance, run.instance()))
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
		if (isUnlikely(instance, passes, workingOutcomes))
			return Verdict.PASS;
		if (passes < MAXIMUM_TRIGGERING_RUNS)
			return null;
		return Verdict.FAIL;
	}

	/**
	 * Indicates whether a passing streak has probability below 10% under the working candidate's smoothed pass rate.
	 *
	 * @param instance the triggering trial content
	 * @param streak the attempt's passing streak length
	 * @param workingOutcomes the working candidate's adopted records
	 * @return {@code true} when {@code (passes + 1)^streak * 10 < (outcomes + 2)^streak}, compared exactly
	 */
	private static boolean isUnlikely(TrialInstance instance, int streak, List<InstanceOutcomes> workingOutcomes)
	{
		BigInteger passes = BigInteger.ONE;
		BigInteger outcomes = BigInteger.TWO;
		for (InstanceOutcomes record : workingOutcomes)
		{
			if (isSameContent(instance, record.instance()))
			{
				passes = passes.add(BigInteger.valueOf(record.passes()));
				outcomes = outcomes.add(BigInteger.valueOf(record.outcomes()));
			}
		}
		return passes.pow(streak).multiply(BigInteger.TEN).compareTo(outcomes.pow(streak)) < 0;
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
