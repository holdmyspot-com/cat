package com.holdmyspot.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import com.holdmyspot.cat.common.optimizer.campaign.Harness;
import com.holdmyspot.cat.common.optimizer.inventory.Sha256;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The guard pilot of one prompt test in one evaluator: its development controls in the test's weakest cell, which
 * show how often the agent passes each assertion without the target prompts. The trials enter no count and spend no
 * allowance.
 * <p>
 * <b>Thread Safety:</b> This class is thread-safe.
 *
 * @param testId the prompt test
 * @param definition the identity of the prompt test and the requirements that it checks when the pilot ran; a pilot
 *   whose test has another identity no longer applies
 * @param cellId the cell that the trials ran in
 * @param control the identity of the conditions that the trials ran in: the control source's files, the session
 *   environment version and the judge version
 * @param seed the seed of the development stream that draws the trials' test cases, which a resumed pilot draws again
 * @param trials the trials that the proposed built-in tools or judge decided, in draw order
 * @param failedTrials why each trial that the proposed built-in tools could not decide ended, one entry per trial
 * @param stopReason why the pilot stopped before running every trial, or {@code null} if it ran every trial
 * @param paused {@code true} if the pilot stopped because one of its trials ended without an outcome too many times,
 *   which the user answers by choosing to retry or to stop; {@code false} if the pilot ran every trial or stopped for
 *   another reason
 * @param pendingTrials recovery and earlier infrastructure attempts of each undecided trial, by draw position
 * @param evaluator the evaluator that qualifies a shared result, or {@code null} for a single evaluator
 */
public record PilotResult(String testId, Sha256 definition, String cellId, Sha256 control, long seed,
	List<PilotTrial> trials, List<String> failedTrials, String stopReason, boolean paused,
	Map<Integer, PilotTrialRecovery> pendingTrials, Harness evaluator)
{
	/**
	 * Creates a pilot result.
	 *
	 * @param testId the prompt test
	 * @param definition the identity of the prompt test and the requirements that it checks when the pilot ran
	 * @param cellId the cell that the trials ran in
	 * @param control the identity of the conditions that the trials ran in
	 * @param seed the seed of the development stream that draws the trials' test cases
	 * @param trials the trials that the proposed built-in tools or judge decided, in draw order
	 * @param failedTrials why each trial that the proposed built-in tools could not decide ended, one entry per trial
	 * @param stopReason why the pilot stopped before running every trial, or {@code null} if it ran every trial
	 * @param paused {@code true} if the pilot stopped because one of its trials ended without an outcome too many times
	 * @param pendingTrials recovery of undecided trials, by draw position
	 * @param evaluator the evaluator that qualifies a shared result, or {@code null} for a single evaluator
	 * @throws NullPointerException if a required argument, trial, failure, pending position or pending state is null
	 * @throws IllegalArgumentException if {@code testId}, {@code cellId}, a reason in {@code failedTrials} or a non-null
	 *   {@code stopReason} are blank, two trials hold one position, or {@code paused} is {@code true} and
	 *   {@code stopReason} is null, a pending position is already decided or outside the pilot, or a shared cell's
	 *   evaluator does not match the result's evaluator
	 */
	public PilotResult
	{
		requireThat(testId, "testId").isNotBlank();
		requireThat(definition, "definition").isNotNull();
		requireThat(cellId, "cellId").isNotBlank();
		requireThat(control, "control").isNotNull();
		if (evaluator != null && !cellId.startsWith(evaluator.commandName() + ":"))
			throw new IllegalArgumentException("Pilot cell " + cellId + " does not belong to " + evaluator);
		trials = List.copyOf(trials);
		Set<Integer> positions = new HashSet<>();
		for (PilotTrial trial : trials)
		{
			if (!positions.add(trial.position()))
				throw new IllegalArgumentException("Two trials hold the position " + trial.position());
		}
		pendingTrials = Map.copyOf(pendingTrials);
		for (int pending : pendingTrials.keySet())
		{
			if (pending < 0 || pending >= GuardPilot.TRIALS || positions.contains(pending))
				throw new IllegalArgumentException("Invalid undecided trial position " + pending);
		}
		failedTrials = List.copyOf(failedTrials);
		for (String reason : failedTrials)
			requireThat(reason, "reason").isNotBlank();
		if (stopReason != null)
			requireThat(stopReason, "stopReason").isNotBlank();
		else if (paused)
			throw new IllegalArgumentException("A paused pilot must say why it paused");
	}

	/**
	 * Creates a result for a single evaluator.
	 *
	 * @param testId the prompt test
	 * @param definition the identity of the prompt test and the requirements that it checks
	 * @param cellId the cell that the trials ran in
	 * @param control the identity of the conditions that the trials ran in
	 * @param seed the seed of the development stream
	 * @param trials the decided trials in draw order
	 * @param failedTrials why each undecided trial ended
	 * @param stopReason why the pilot stopped early, or {@code null} if it ran every trial
	 * @param paused {@code true} if the pilot stopped for the user's choice to retry or to stop
	 * @param pendingTrials recovery of undecided trials, by draw position
	 * @throws NullPointerException if any required argument, trial, failure or pending position or state is null
	 * @throws IllegalArgumentException if an ID, a failure or a non-null stop reason is blank, two trials share a
	 *   position, a paused pilot has no stop reason, or a pending position is already decided or outside the pilot
	 */
	public PilotResult(String testId, Sha256 definition, String cellId, Sha256 control, long seed,
		List<PilotTrial> trials, List<String> failedTrials, String stopReason, boolean paused,
		Map<Integer, PilotTrialRecovery> pendingTrials)
	{
		this(testId, definition, cellId, control, seed, trials, failedTrials, stopReason, paused, pendingTrials, null);
	}

	/**
	 * Creates a pilot result with no tracked undecided trials.
	 *
	 * @param testId the prompt test
	 * @param definition the identity of the prompt test and the requirements that it checks when the pilot ran
	 * @param cellId the cell that the trials ran in
	 * @param control the identity of the conditions that the trials ran in
	 * @param seed the seed of the development stream that draws the trials' test cases
	 * @param trials the trials that the proposed built-in tools or judge decided, in draw order
	 * @param failedTrials why each trial that the proposed built-in tools could not decide ended, one entry per trial
	 * @param stopReason why the pilot stopped before running every trial, or {@code null} if it ran every trial
	 * @param paused {@code true} if the pilot stopped because one of its trials ended without an outcome too many times
	 * @throws NullPointerException if any argument other than {@code stopReason} is null, or {@code trials} or
	 *   {@code failedTrials} contains null
	 * @throws IllegalArgumentException if {@code testId}, {@code cellId}, a reason in {@code failedTrials} or a non-null
	 *   {@code stopReason} are blank, two trials hold one position, or a paused pilot has no stop reason
	 */
	public PilotResult(String testId, Sha256 definition, String cellId, Sha256 control, long seed,
		List<PilotTrial> trials, List<String> failedTrials, String stopReason, boolean paused)
	{
		this(testId, definition, cellId, control, seed, trials, failedTrials, stopReason, paused, Map.of());
	}

	/**
	 * Returns the key of this test and evaluator in a pilot checkpoint.
	 *
	 * @return the checkpoint key
	 */
	public String key()
	{
		return key(testId, evaluator);
	}

	/**
	 * Returns the key of a test and evaluator in a pilot checkpoint.
	 *
	 * @param testId the prompt test
	 * @param evaluator the evaluator that qualifies a shared result, or {@code null} for a single evaluator
	 * @return the checkpoint key
	 * @throws NullPointerException if {@code testId} is null
	 */
	public static String key(String testId, Harness evaluator)
	{
		requireThat(testId, "testId").isNotNull();
		if (evaluator == null)
			return testId;
		return evaluator.commandName() + ":" + testId;
	}

	/**
	 * Returns the number of trials that decided an assertion.
	 *
	 * @param assertionId the assertion
	 * @return the number of trials that passed or failed it
	 * @throws NullPointerException if {@code assertionId} is null
	 */
	public int decided(String assertionId)
	{
		requireThat(assertionId, "assertionId").isNotNull();
		int decided = 0;
		for (PilotTrial trial : trials)
		{
			if (trial.assertions().containsKey(assertionId))
				++decided;
		}
		return decided;
	}

	/**
	 * Returns the number of trials that passed an assertion.
	 *
	 * @param assertionId the assertion
	 * @return the number of trials that passed it
	 * @throws NullPointerException if {@code assertionId} is null
	 */
	public int passes(String assertionId)
	{
		requireThat(assertionId, "assertionId").isNotNull();
		int passes = 0;
		for (PilotTrial trial : trials)
		{
			if (trial.assertions().getOrDefault(assertionId, false))
				++passes;
		}
		return passes;
	}

	/**
	 * Indicates whether the trials that decided an assertion passed it less often than a rate. An assertion that no
	 * trial decided is not below any rate.
	 *
	 * @param assertionId the assertion
	 * @param rate a rate between 0 and 1, such as the contract's minimum pass rate
	 * @return {@code true} if at least one trial decided the assertion and its passes divided by those trials is less
	 *   than {@code rate}
	 * @throws NullPointerException if any of the arguments are null
	 */
	public boolean isBelow(String assertionId, BigDecimal rate)
	{
		requireThat(rate, "rate").isNotNull();
		int decided = decided(assertionId);
		if (decided == 0)
			return false;
		BigDecimal passes = BigDecimal.valueOf(passes(assertionId));
		return passes.compareTo(rate.multiply(BigDecimal.valueOf(decided))) < 0;
	}

	/**
	 * Indicates whether this pilot still describes a prompt test: it ran every trial, every trial ended with a decision,
	 * and the test's definition, its weakest cell and the conditions of its control trials are the ones that it ran
	 * with.
	 *
	 * @param definition the current identity of the prompt test and the requirements that it checks
	 * @param cellId the test's current weakest cell
	 * @param control the identity of the current conditions of control trials: the control source's files, the
	 *   session environment version and the judge version
	 * @return {@code true} if the pilot need not run again
	 * @throws NullPointerException if any of the arguments are null
	 */
	public boolean appliesTo(Sha256 definition, String cellId, Sha256 control)
	{
		return stopReason == null && failedTrials.isEmpty() && pendingTrials.isEmpty() &&
			ranIn(definition, cellId, control);
	}

	/**
	 * Indicates whether this pilot's trials ran under the current definition, cell and conditions, so that a pilot that
	 * did not finish keeps them and runs only the trials that it lacks.
	 *
	 * @param definition the current identity of the prompt test and the requirements that it checks
	 * @param cellId the test's current weakest cell
	 * @param control the identity of the current conditions of control trials: the control source's files, the
	 *   session environment version and the judge version
	 * @return {@code true} if the trials still describe the test
	 * @throws NullPointerException if any of the arguments are null
	 */
	public boolean ranIn(Sha256 definition, String cellId, Sha256 control)
	{
		requireThat(definition, "definition").isNotNull();
		requireThat(cellId, "cellId").isNotNull();
		requireThat(control, "control").isNotNull();
		return this.definition.equals(definition) && this.cellId.equals(cellId) && this.control.equals(control);
	}
}
