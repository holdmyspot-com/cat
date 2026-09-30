package io.github.cowwoc.cat.common.optimizer.contract;

import java.util.Objects;

/**
 * The frozen authority that decides a semantic compliance result.
 *
 * @param model evaluator model
 * @param reasoningProfile evaluator reasoning profile
 * @param rubric evaluator rubric
 * @param decisionRule evaluator decision rule
 * @param resultSchema evaluator result schema
 */
public record EvaluatorDefinition(String model, String reasoningProfile, String rubric, String decisionRule,
	EvaluatorResultSchema resultSchema)
{
	/**
 * Validates an evaluator definition.
 */
	public EvaluatorDefinition
	{
		ContractValues.nonBlank(model, "evaluator model");
		ContractValues.nonBlank(reasoningProfile, "evaluator reasoning profile");
		ContractValues.nonBlank(rubric, "evaluator rubric");
		ContractValues.nonBlank(decisionRule, "evaluator decision rule");
		Objects.requireNonNull(resultSchema, "resultSchema");
	}
}
