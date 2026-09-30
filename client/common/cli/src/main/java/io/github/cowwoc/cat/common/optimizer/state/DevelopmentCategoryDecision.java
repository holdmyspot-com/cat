package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentAllocationPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A representative category batch selected after balanced initial coverage.
 *
 * @param schemaVersion supported decision schema
 * @param campaignId owning campaign
 * @param lineage confirmed lineage and contract
 * @param candidateId measured candidate
 * @param measurementEpochRef measurement identity
 * @param sourceRevisionRef counted evidence used at the choice boundary
 * @param allocationPolicy confirmed allocation policy
 * @param confirmedCategories confirmed category order
 * @param stoppedCategories categories excluded by higher-priority decisions
 * @param categoryId selected category
 * @param purpose representative development purpose
 * @param batchNumber zero-based development batch number
 * @param position turn and coverage cursor before this batch
 * @param nextPosition turn and coverage cursor after this batch completes
 * @param reviewAuthorizationRefs responses authorizing continued sampling past a review checkpoint
 */
record DevelopmentCategoryDecision(int schemaVersion, String campaignId, LineageKey lineage,
	String candidateId, EvidenceRef measurementEpochRef, EvidenceRef sourceRevisionRef,
	DevelopmentAllocationPolicy allocationPolicy, List<String> confirmedCategories,
	List<String> stoppedCategories, String categoryId, EvidencePurpose purpose, int batchNumber,
	DevelopmentBatchSelector.Progress position, DevelopmentBatchSelector.Progress nextPosition,
	List<EvidenceRef> reviewAuthorizationRefs)
{
	/** Supported decision schema. */
	static final int SCHEMA_VERSION = 2;

	/**
	 * Validates the retained category, identities, and batch position.
	 *
	 * @param schemaVersion supported decision schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed lineage and contract
	 * @param candidateId measured candidate
	 * @param measurementEpochRef measurement identity
	 * @param sourceRevisionRef counted evidence used at the choice boundary
	 * @param allocationPolicy confirmed allocation policy
	 * @param confirmedCategories confirmed category order
	 * @param stoppedCategories categories excluded by higher-priority decisions
	 * @param categoryId selected category
	 * @param purpose representative development purpose
	 * @param batchNumber zero-based development batch number
	 * @param position turn and coverage cursor before this batch
	 * @param nextPosition turn and coverage cursor after this batch completes
	 * @param reviewAuthorizationRefs responses authorizing continued sampling past a review checkpoint
	 */
	DevelopmentCategoryDecision
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(allocationPolicy, "allocationPolicy");
		Objects.requireNonNull(position, "position");
		Objects.requireNonNull(nextPosition, "nextPosition");
		confirmedCategories = List.copyOf(confirmedCategories);
		stoppedCategories = List.copyOf(stoppedCategories);
		reviewAuthorizationRefs = List.copyOf(reviewAuthorizationRefs);
		Set<String> confirmed = new HashSet<>(confirmedCategories);
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			candidateId == null || candidateId.isBlank() ||
			measurementEpochRef == null || !measurementEpochRef.collection().equals("measurement-epochs") ||
			sourceRevisionRef == null ||
			!sourceRevisionRef.collection().equals("development-evidence-revisions") ||
			confirmedCategories.isEmpty() || confirmedCategories.stream().anyMatch(id -> id == null || id.isBlank()) ||
			confirmed.size() != confirmedCategories.size() ||
			stoppedCategories.stream().anyMatch(id -> !confirmed.contains(id)) ||
			new HashSet<>(reviewAuthorizationRefs).size() != reviewAuthorizationRefs.size() ||
			reviewAuthorizationRefs.stream().anyMatch(ref -> ref == null ||
				!ref.collection().equals("development-review-responses")) ||
			!confirmed.contains(categoryId) || stoppedCategories.contains(categoryId) ||
			purpose != EvidencePurpose.DEVELOPMENT || batchNumber < confirmedCategories.size() ||
			position.coverageCursor() >= confirmedCategories.size() ||
			nextPosition.coverageCursor() >= confirmedCategories.size() ||
			position.turn() == nextPosition.turn())
			throw new IllegalArgumentException("Development category decision is invalid");
	}
}
