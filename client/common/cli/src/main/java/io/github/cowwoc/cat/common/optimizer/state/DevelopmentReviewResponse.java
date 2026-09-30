package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * A retained user choice for one pending development evidence review.
 *
 * @param schemaVersion supported response schema
 * @param campaignId owning campaign
 * @param lineage confirmed lineage and contract
 * @param requestRef review request being answered
 * @param choice selected response
 * @param nextReviewPoint next representative count, or zero when no extension was chosen
 */
record DevelopmentReviewResponse(int schemaVersion, String campaignId, LineageKey lineage,
	EvidenceRef requestRef, DevelopmentReviewRequest.Choice choice, int nextReviewPoint)
{
	/** Supported response schema. */
	static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the choice shape before it becomes durable evidence.
	 *
	 * @param schemaVersion supported response schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed lineage and contract
	 * @param requestRef review request being answered
	 * @param choice selected response
	 * @param nextReviewPoint next representative count, or zero without an extension
	 */
	DevelopmentReviewResponse
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(choice, "choice");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			requestRef == null || !requestRef.collection().equals("development-review-requests") ||
			choice == DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE && nextReviewPoint <= 0 ||
			choice != DevelopmentReviewRequest.Choice.FURTHER_EVIDENCE && nextReviewPoint != 0)
			throw new IllegalArgumentException("Development review response is invalid");
	}
}
