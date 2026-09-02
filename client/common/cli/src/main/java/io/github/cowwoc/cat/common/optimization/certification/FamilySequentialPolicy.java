package io.github.cowwoc.cat.common.optimization.certification;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Holds every versioned statistical choice used by the family-sequential certification engine.
 */
public final class FamilySequentialPolicy
{
	private final String identifier;
	private final String statisticalRevision;
	private final FamilySequentialDecision.Policy decisionPolicy;
	private final BigDecimal lineageErrorBudget;
	private final CandidateAllowanceLedger.Schedule candidateAllowanceSchedule;

	/**
	 * Creates one immutable statistical policy.
	 *
	 * @param identifier the policy identifier
	 * @param statisticalRevision the exact statistical construction revision
	 * @param target the weakest-family lower-bound target
	 * @param maximumTrialsPerFamily the hard formal-trial cap per family
	 * @param lineageErrorBudget the maximum lineage-wide false-certification probability
	 * @param candidateAllowanceSchedule the summable candidate schedule
	 */
	public FamilySequentialPolicy(String identifier, String statisticalRevision, double target,
		int maximumTrialsPerFamily, BigDecimal lineageErrorBudget,
		CandidateAllowanceLedger.Schedule candidateAllowanceSchedule)
	{
		this.identifier = requireText(identifier, "identifier");
		this.statisticalRevision = requireText(statisticalRevision, "statisticalRevision");
		this.decisionPolicy = new FamilySequentialDecision.Policy(target, maximumTrialsPerFamily);
		Objects.requireNonNull(lineageErrorBudget, "lineageErrorBudget must not be null");
		if (lineageErrorBudget.signum() <= 0 || lineageErrorBudget.compareTo(new BigDecimal("0.05")) > 0)
		{
			throw new IllegalArgumentException(
				"lineageErrorBudget must be greater than zero and no more than 0.05");
		}
		this.lineageErrorBudget = lineageErrorBudget;
		this.candidateAllowanceSchedule = Objects.requireNonNull(candidateAllowanceSchedule,
			"candidateAllowanceSchedule must not be null");
	}

	/**
	 * Returns the policy identifier.
	 *
	 * @return the policy identifier
	 */
	public String identifier()
	{
		return identifier;
	}

	/**
	 * Returns the statistical construction revision.
	 *
	 * @return the statistical revision
	 */
	public String statisticalRevision()
	{
		return statisticalRevision;
	}

	/**
	 * Returns the engine decision policy.
	 *
	 * @return the target and trial cap
	 */
	public FamilySequentialDecision.Policy decisionPolicy()
	{
		return decisionPolicy;
	}

	/**
	 * Returns the exact lineage-wide error budget.
	 *
	 * @return the error budget
	 */
	public BigDecimal lineageErrorBudget()
	{
		return lineageErrorBudget;
	}

	/**
	 * Returns the fixed summable candidate schedule.
	 *
	 * @return the candidate allowance schedule
	 */
	public CandidateAllowanceLedger.Schedule candidateAllowanceSchedule()
	{
		return candidateAllowanceSchedule;
	}

	/**
	 * Validates required open text.
	 *
	 * @param value the proposed text
	 * @param name the parameter name
	 * @return the unchanged text
	 */
	private static String requireText(String value, String name)
	{
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank())
			throw new IllegalArgumentException(name + " must not be blank");
		return value;
	}
}
