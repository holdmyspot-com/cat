package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * The retained treatment of one possible dependence source.
 *
 * @param source named dependence source
 * @param disposition treatment of that source
 * @param evidenceReference evidence supporting the treatment
 */
public record DependenceCondition(DependenceSource source, DependenceDisposition disposition, String evidenceReference)
{
	/**
 * Validates a dependence condition.
 */
	public DependenceCondition
	{
		if (source == null || disposition == null)
			throw new IllegalArgumentException("Dependence condition is incomplete");
		ContractValues.nonBlank(evidenceReference, "dependence evidence reference");
	}
}
