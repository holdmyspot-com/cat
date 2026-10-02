package io.github.cowwoc.cat.common.optimizer.contract;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

/**
 * The result of one check that CAT decides directly from trial evidence: which check ran, whether it passed, and
 * the value it observed.
 *
 * @param check the check
 * @param passed {@code true} if the evidence satisfied the check
 * @param observed what the check observed in the evidence
 */
public record CheckResult(DeterministicCheck check, boolean passed, String observed)
{
	/**
	 * Creates a result.
	 *
	 * @param check the check
	 * @param passed {@code true} if the evidence satisfied the check
	 * @param observed what the check observed in the evidence
	 * @throws NullPointerException if {@code check} or {@code observed} are null
	 * @throws IllegalArgumentException if {@code observed} is blank
	 */
	public CheckResult
	{
		requireThat(check, "check").isNotNull();
		requireThat(observed, "observed").isNotBlank();
	}

	/**
	 * Returns the result as one line: the requirement, the check's kind and parameters, the outcome and the observed
	 * value.
	 *
	 * @return the description
	 */
	public String describe()
	{
		String outcome;
		if (passed)
			outcome = "passed";
		else
			outcome = "failed";
		CheckCriterion criterion = check.criterion();
		return "Requirement `" + check.requirementId() + "`, " + criterion.kind() + " check (" +
			criterion.describe() + "): " + outcome + ". Observed: " + observed;
	}
}
