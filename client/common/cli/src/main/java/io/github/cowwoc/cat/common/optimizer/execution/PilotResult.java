package io.github.cowwoc.cat.common.optimizer.execution;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.Sha256;

import java.math.BigDecimal;
import java.util.List;

/**
 * The guard pilot of one prompt test: its control trials of development test cases in the test's weakest cell, which
 * show how often the agent passes each assertion without the target prompts. The trials enter no count and spend no
 * allowance.
 * <p>
 * <b>Thread Safety:</b> This class is immutable and thread-safe.
 *
 * @param testId the prompt test
 * @param definition the identity of the prompt test and the requirements that it checks when the pilot ran; a pilot
 *   whose test has another identity no longer applies
 * @param cellId the cell that the trials ran in
 * @param control the identity of the control source's files that the trials ran on
 * @param trials the trials that the proposed built-in tools or judge decided, in completion order
 * @param failedRuns the number of trials that ended without a decision, such as a session that failed or a judge
 *   whose output never matched its result schema
 * @param stopReason why the pilot stopped before running every trial, or {@code null} if it ran every trial
 */
public record PilotResult(String testId, Sha256 definition, String cellId, Sha256 control, List<PilotTrial> trials,
	int failedRuns, String stopReason)
{
	/**
	 * Creates a pilot result.
	 *
	 * @param testId the prompt test
	 * @param definition the identity of the prompt test and the requirements that it checks when the pilot ran
	 * @param cellId the cell that the trials ran in
	 * @param control the identity of the control source's files that the trials ran on
	 * @param trials the trials that the proposed built-in tools or judge decided, in completion order
	 * @param failedRuns the number of trials that ended without a decision
	 * @param stopReason why the pilot stopped before running every trial, or {@code null} if it ran every trial
	 * @throws NullPointerException if any argument other than {@code stopReason} is null, or {@code trials} contains
	 *   null
	 * @throws IllegalArgumentException if {@code testId}, {@code cellId} or a non-null {@code stopReason} are blank, or
	 *   {@code failedRuns} is negative
	 */
	public PilotResult
	{
		requireThat(testId, "testId").isNotBlank();
		requireThat(definition, "definition").isNotNull();
		requireThat(cellId, "cellId").isNotBlank();
		requireThat(control, "control").isNotNull();
		trials = List.copyOf(trials);
		requireThat(failedRuns, "failedRuns").isNotNegative();
		if (stopReason != null)
			requireThat(stopReason, "stopReason").isNotBlank();
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
	 * Indicates whether this pilot still describes a prompt test: it ran every trial, and the test's definition, its
	 * weakest cell and the control source's files are the ones that it ran with.
	 *
	 * @param definition the current identity of the prompt test and the requirements that it checks
	 * @param cellId the test's current weakest cell
	 * @param control the identity of the current control source's files
	 * @return {@code true} if the pilot need not run again
	 * @throws NullPointerException if any of the arguments are null
	 */
	public boolean appliesTo(Sha256 definition, String cellId, Sha256 control)
	{
		requireThat(definition, "definition").isNotNull();
		requireThat(cellId, "cellId").isNotNull();
		requireThat(control, "control").isNotNull();
		return stopReason == null && this.definition.equals(definition) && this.cellId.equals(cellId) &&
			this.control.equals(control);
	}
}
