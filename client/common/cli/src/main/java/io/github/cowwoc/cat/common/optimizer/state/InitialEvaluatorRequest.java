package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Binds one semantic evaluation to a confirmed trial and its adopted native completion.
 *
 * @param schemaVersion supported request schema
 * @param campaignId owning campaign
 * @param lineage confirmed evaluator authority
 * @param assignmentRef assigned trial selection
 * @param executionRef adopted native trial execution
 * @param completionRef indexed successful native completion
 */
public record InitialEvaluatorRequest(int schemaVersion, String campaignId, LineageKey lineage,
	EvidenceRef assignmentRef, EvidenceRef executionRef, EvidenceRef completionRef)
{
	/** Supported semantic request schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the retained evaluation inputs.
	 *
	 * @param schemaVersion supported request schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed evaluator authority
	 * @param assignmentRef assigned trial selection
	 * @param executionRef adopted native trial execution
	 * @param completionRef indexed successful native completion
	 */
	public InitialEvaluatorRequest
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		Objects.requireNonNull(executionRef, "executionRef");
		Objects.requireNonNull(completionRef, "completionRef");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!assignmentRef.collection().equals("trial-assignments") ||
			!executionRef.collection().equals("trial-execution-records") ||
			!completionRef.collection().equals("native-run-completions"))
			throw new IllegalArgumentException("Initial evaluator request identity is invalid");
	}
}
