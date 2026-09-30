package io.github.cowwoc.cat.common.optimizer.contract;

import java.math.BigDecimal;

/**
 * The confirmed, frozen formal certification procedure.
 *
 * @param method statistical method
 * @param threshold required compliance threshold
 * @param planningRate expected compliance used only to size the fixed sample
 * @param minimumPower minimum pass probability at the planning rate
 * @param allowanceFormula fixed lineage allowance formula
 * @param freshSessionPolicy session-isolation policy
 * @param assumptions formal validity assumptions
 */
public record FormalProcedure(FormalMethod method, BigDecimal threshold, BigDecimal planningRate,
	BigDecimal minimumPower, String allowanceFormula, FreshSessionPolicy freshSessionPolicy,
	FormalAssumptions assumptions)
{
	/**
 * Validates a formal procedure.
 */
	public FormalProcedure
	{
		if (method == null || threshold == null || planningRate == null || minimumPower == null ||
			freshSessionPolicy == null || assumptions == null)
			throw new IllegalArgumentException("Formal procedure is incomplete");
		ContractValues.nonBlank(allowanceFormula, "allowance formula");
	}
}
