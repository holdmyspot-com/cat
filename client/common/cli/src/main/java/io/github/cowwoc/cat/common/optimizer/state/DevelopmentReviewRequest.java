package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * An unresolved category's retained evidence review before another representative draw.
 *
 * @param schemaVersion supported request schema
 * @param campaignId owning campaign
 * @param lineage confirmed lineage and contract
 * @param candidateId measured candidate
 * @param measurementEpochRef measurement identity
 * @param categoryId category awaiting a choice
 * @param sourceRevisionRef counted evidence at the checkpoint
 * @param compliantOutcomes compliant representative outcomes for the category
 * @param totalOutcomes total representative outcomes for the category
 * @param reviewPoint minimum outcome count requiring this review
 * @param precedingResponseRef response that set this checkpoint, absent for the confirmed first checkpoint
 */
record DevelopmentReviewRequest(int schemaVersion, String campaignId, LineageKey lineage,
	String candidateId, EvidenceRef measurementEpochRef, String categoryId, EvidenceRef sourceRevisionRef,
	int compliantOutcomes, int totalOutcomes, int reviewPoint, Optional<EvidenceRef> precedingResponseRef)
{
	/** Supported request schema. */
	static final int SCHEMA_VERSION = 2;

	/** Choices available when reviewing an unresolved category. */
	enum Choice
	{
		FURTHER_EVIDENCE,
		TARGETED_OPTIMIZATION,
		STOP
	}

	/**
	 * Validates the retained review identity and counted evidence.
	 *
	 * @param schemaVersion supported request schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed lineage and contract
	 * @param candidateId measured candidate
	 * @param measurementEpochRef measurement identity
	 * @param categoryId category awaiting a choice
	 * @param sourceRevisionRef counted evidence at the checkpoint
	 * @param compliantOutcomes compliant representative outcomes for the category
	 * @param totalOutcomes total representative outcomes for the category
	 * @param reviewPoint minimum outcome count requiring this review
	 * @param precedingResponseRef response that set this checkpoint, absent for the first checkpoint
	 */
	DevelopmentReviewRequest
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(precedingResponseRef, "precedingResponseRef");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			candidateId == null || candidateId.isBlank() || categoryId == null || categoryId.isBlank() ||
			measurementEpochRef == null || !measurementEpochRef.collection().equals("measurement-epochs") ||
			sourceRevisionRef == null ||
			!sourceRevisionRef.collection().equals("development-evidence-revisions") ||
			reviewPoint <= 0 || compliantOutcomes < 0 || compliantOutcomes > totalOutcomes ||
			totalOutcomes < reviewPoint || precedingResponseRef.stream().anyMatch(ref ->
				!ref.collection().equals("development-review-responses")))
			throw new IllegalArgumentException("Development review request is invalid");
	}

	/**
	 * Lists the product choices available for this pending review.
	 *
	 * @return further evidence, targeted optimization, or stopping
	 */
	List<Choice> choices()
	{
		return List.of(Choice.values());
	}
}
