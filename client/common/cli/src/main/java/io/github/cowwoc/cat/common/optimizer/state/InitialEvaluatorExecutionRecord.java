package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Joins one semantic evaluator request and indexed native completion before labeling a trial.
 *
 * @param schemaVersion supported execution record schema
 * @param campaignId owning campaign
 * @param semanticRequestRef frozen semantic evaluator request
 * @param attemptRef indexed evaluator transport attempt
 * @param completionRef indexed typed native completion
 */
public record InitialEvaluatorExecutionRecord(int schemaVersion, String campaignId,
	EvidenceRef semanticRequestRef, EvidenceRef attemptRef, EvidenceRef completionRef)
{
	/** Supported evaluator execution record schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the retained evaluator execution evidence.
	 *
	 * @param schemaVersion supported execution record schema
	 * @param campaignId owning campaign
	 * @param semanticRequestRef frozen semantic evaluator request
	 * @param attemptRef indexed evaluator transport attempt
	 * @param completionRef indexed typed native completion
	 */
	public InitialEvaluatorExecutionRecord
	{
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		Objects.requireNonNull(attemptRef, "attemptRef");
		Objects.requireNonNull(completionRef, "completionRef");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!semanticRequestRef.collection().equals("evaluator-requests") ||
			!attemptRef.collection().equals("evaluator-transport-attempts") ||
			!completionRef.collection().equals("native-run-completions"))
			throw new IllegalArgumentException("Evaluator execution record identity is invalid");
	}
}
