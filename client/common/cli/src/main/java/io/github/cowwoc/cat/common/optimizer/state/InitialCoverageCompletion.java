package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable source revision for the next category in balanced initial coverage.
 *
 * @param schemaVersion supported completion schema
 * @param campaignId owning campaign
 * @param lineage confirmed lineage and contract
 * @param candidateId measured candidate
 * @param measurementEpochRef measurement identity for the completed batch
 * @param previousDecisionRef decision that authorized the completed batch
 * @param completedCategoryId category whose first batch finished
 * @param outcomeRefs counted semantic outcomes in assignment ordinal order
 */
record InitialCoverageCompletion(int schemaVersion, String campaignId, LineageKey lineage,
	String candidateId, EvidenceRef measurementEpochRef, EvidenceRef previousDecisionRef,
	String completedCategoryId, List<EvidenceRef> outcomeRefs)
{
	/** Supported completion schema. */
	static final int SCHEMA_VERSION = 1;

	/**
	 * Requires distinct semantic outcome references for one completed batch.
	 *
	 * @param schemaVersion supported completion schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed lineage and contract
	 * @param candidateId measured candidate
	 * @param measurementEpochRef measurement identity for the completed batch
	 * @param previousDecisionRef decision that authorized the completed batch
	 * @param completedCategoryId category whose first batch finished
	 * @param outcomeRefs counted semantic outcomes in assignment ordinal order
	 */
	InitialCoverageCompletion
	{
		Objects.requireNonNull(lineage, "lineage");
		outcomeRefs = List.copyOf(outcomeRefs);
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			candidateId == null || candidateId.isBlank() || completedCategoryId == null ||
			completedCategoryId.isBlank() || measurementEpochRef == null ||
			!measurementEpochRef.collection().equals("measurement-epochs") ||
			previousDecisionRef == null || !previousDecisionRef.collection().equals("category-decisions") ||
			outcomeRefs.isEmpty() || new HashSet<>(outcomeRefs).size() != outcomeRefs.size() ||
			outcomeRefs.stream().anyMatch(ref -> ref == null || !ref.collection().equals("trial-outcomes")))
			throw new IllegalArgumentException("Initial coverage completion is invalid");
	}
}
