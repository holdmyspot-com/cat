package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.Objects;

/**
 * One validated semantic judgment returned under the frozen evaluator schema.
 *
 * @param label semantic compliance decision
 * @param reason nonblank explanation of the decision
 */
public record EvaluatorResponse(EvaluatorLabel label, String reason)
{
	/**
	 * Validates one semantic judgment.
	 *
	 * @param label semantic compliance decision
	 * @param reason nonblank explanation of the decision
	 */
	public EvaluatorResponse
	{
		Objects.requireNonNull(label, "label");
		if (reason == null || reason.isBlank())
			throw new IllegalArgumentException("Evaluator reason must not be blank");
	}
}
