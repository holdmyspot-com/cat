package io.github.cowwoc.cat.common.optimizer.contract;

/** The supported machine-readable shape of a frozen evaluator response. */
public enum EvaluatorResultSchema
{
	/** One compliance label and one nonblank reason in an exact JSON object. */
	LABEL_AND_REASON_JSON_V1;

	/**
	 * Describes the response fields to the evaluator.
	 *
	 * @return complete result instruction
	 */
	public String instruction()
	{
		return "Return only one JSON object with exactly two string fields: " +
			"label, which is COMPLIANT or NONCOMPLIANT, and reason, which explains the judgment.";
	}
}
