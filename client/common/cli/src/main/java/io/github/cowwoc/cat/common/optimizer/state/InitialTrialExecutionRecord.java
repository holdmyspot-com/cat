package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Joins an assigned trial and indexed native completion before semantic evaluation.
 *
 * @param schemaVersion supported execution record schema
 * @param campaignId owning campaign
 * @param assignmentRef committed trial assignment
 * @param attemptRef indexed trial transport attempt
 * @param completionRef indexed typed native completion
 */
public record InitialTrialExecutionRecord(int schemaVersion, String campaignId, EvidenceRef assignmentRef,
	EvidenceRef attemptRef, EvidenceRef completionRef)
{
	/** Supported initial execution record schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the intrinsic evidence collections and identity.
	 *
	 * @param schemaVersion supported execution record schema
	 * @param campaignId owning campaign
	 * @param assignmentRef committed trial assignment
	 * @param attemptRef indexed trial transport attempt
	 * @param completionRef indexed typed native completion
	 */
	public InitialTrialExecutionRecord
	{
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		Objects.requireNonNull(attemptRef, "attemptRef");
		Objects.requireNonNull(completionRef, "completionRef");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!assignmentRef.collection().equals("trial-assignments") ||
			!attemptRef.collection().equals("trial-transport-attempts") ||
			!completionRef.collection().equals("native-run-completions"))
			throw new IllegalArgumentException("Initial trial execution record identity is invalid");
	}
}
