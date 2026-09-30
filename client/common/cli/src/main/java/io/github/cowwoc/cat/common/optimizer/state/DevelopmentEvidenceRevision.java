package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Counted development outcomes visible when a category choice is made.
 *
 * @param schemaVersion supported revision schema
 * @param campaignId owning campaign
 * @param lineage confirmed lineage and contract
 * @param candidateId measured candidate
 * @param measurementEpochRef measurement identity
 * @param sourceCounter campaign transition counter at the choice boundary
 * @param outcomeRefs counted outcomes in transition order through that counter
 */
record DevelopmentEvidenceRevision(int schemaVersion, String campaignId, LineageKey lineage,
	String candidateId, EvidenceRef measurementEpochRef, long sourceCounter, List<EvidenceRef> outcomeRefs)
{
	/** Supported revision schema. */
	static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the revision identity and distinct outcome references.
	 *
	 * @param schemaVersion supported revision schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed lineage and contract
	 * @param candidateId measured candidate
	 * @param measurementEpochRef measurement identity
	 * @param sourceCounter campaign transition counter at the choice boundary
	 * @param outcomeRefs counted outcomes in transition order through that counter
	 */
	DevelopmentEvidenceRevision
	{
		Objects.requireNonNull(lineage, "lineage");
		outcomeRefs = List.copyOf(outcomeRefs);
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			candidateId == null || candidateId.isBlank() ||
			measurementEpochRef == null || !measurementEpochRef.collection().equals("measurement-epochs") ||
			sourceCounter <= 0 || outcomeRefs.isEmpty() ||
			new HashSet<>(outcomeRefs).size() != outcomeRefs.size() ||
			outcomeRefs.stream().anyMatch(ref -> ref == null || !ref.collection().equals("trial-outcomes")))
			throw new IllegalArgumentException("Development evidence revision is invalid");
	}
}
