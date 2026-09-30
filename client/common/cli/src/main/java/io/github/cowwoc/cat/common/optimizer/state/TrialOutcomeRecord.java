package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResponse;
import io.github.cowwoc.cat.common.optimizer.contract.EvaluatorResultSchema;
import java.util.Objects;

/**
 * Retains one validated semantic outcome under its assigned trial and frozen evaluator.
 *
 * @param schemaVersion supported record schema
 * @param campaignId owning campaign
 * @param lineage confirmed contract lineage
 * @param assignmentRef assigned trial
 * @param trialExecutionRef adopted native trial evidence
 * @param evaluatorExecutionRef adopted native evaluator evidence
 * @param resultSchema frozen evaluator response schema
 * @param response validated semantic judgment
 */
public record TrialOutcomeRecord(int schemaVersion, String campaignId, LineageKey lineage,
	EvidenceRef assignmentRef, EvidenceRef trialExecutionRef, EvidenceRef evaluatorExecutionRef,
	EvaluatorResultSchema resultSchema, EvaluatorResponse response)
{
	/** Supported trial outcome record schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the retained outcome identity.
	 *
	 * @param schemaVersion supported record schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed contract lineage
	 * @param assignmentRef assigned trial
	 * @param trialExecutionRef adopted native trial evidence
	 * @param evaluatorExecutionRef adopted native evaluator evidence
	 * @param resultSchema frozen evaluator response schema
	 * @param response validated semantic judgment
	 */
	public TrialOutcomeRecord
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		Objects.requireNonNull(trialExecutionRef, "trialExecutionRef");
		Objects.requireNonNull(evaluatorExecutionRef, "evaluatorExecutionRef");
		Objects.requireNonNull(resultSchema, "resultSchema");
		Objects.requireNonNull(response, "response");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!assignmentRef.collection().equals("trial-assignments") ||
			!trialExecutionRef.collection().equals("trial-execution-records") ||
			!evaluatorExecutionRef.collection().equals("evaluator-execution-records"))
			throw new IllegalArgumentException("Trial outcome identity is invalid");
	}
}
