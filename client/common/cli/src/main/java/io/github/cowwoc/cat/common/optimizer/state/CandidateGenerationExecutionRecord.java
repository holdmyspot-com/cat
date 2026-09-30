package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Joins a generation request and indexed native completion before semantic candidate admission.
 *
 * @param schemaVersion supported execution record schema
 * @param campaignId owning campaign
 * @param requestRef committed generation request
 * @param attemptRef indexed generation transport attempt
 * @param completionRef indexed typed native completion
 */
public record CandidateGenerationExecutionRecord(int schemaVersion, String campaignId, EvidenceRef requestRef,
	EvidenceRef attemptRef, EvidenceRef completionRef)
{
	/** Supported generation execution record schema. */
	public static final int SCHEMA_VERSION = 1;
	/** Immutable generation execution records. */
	public static final String COLLECTION = "candidate-execution-records";

	/**
	 * Validates the intrinsic evidence collections and identity.
	 *
	 * @param schemaVersion supported execution record schema
	 * @param campaignId owning campaign
	 * @param requestRef committed generation request
	 * @param attemptRef indexed generation transport attempt
	 * @param completionRef indexed typed native completion
	 */
	public CandidateGenerationExecutionRecord
	{
		Objects.requireNonNull(requestRef, "requestRef");
		Objects.requireNonNull(attemptRef, "attemptRef");
		Objects.requireNonNull(completionRef, "completionRef");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!requestRef.collection().equals("candidate-generation-requests") ||
			!attemptRef.collection().equals("candidate-native-attempts") ||
			!completionRef.collection().equals("native-run-completions"))
			throw new IllegalArgumentException("Candidate generation execution record identity is invalid");
	}
}
