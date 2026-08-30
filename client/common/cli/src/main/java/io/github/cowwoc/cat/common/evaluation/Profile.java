package io.github.cowwoc.cat.common.evaluation;

/**
 * Names the model profile that a harness runner resolved for one evaluator role.
 *
 * @param owner the artifact or default policy owner
 * @param model the effective model
 * @param effort the effective reasoning effort
 */
public record Profile(String owner, String model, String effort)
{
	/**
	 * Returns the stable profile label persisted with the evaluation record.
	 *
	 * @return the owner, model, and effort joined into one profile label
	 */
	public String label()
	{
		return owner + "-" + model + "-" + effort;
	}
}
