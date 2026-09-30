package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentAllocationPolicy;
import java.util.List;
import java.util.Objects;

/**
 * A category's first representative batch chosen before its first trial draw.
 *
 * @param schemaVersion supported decision schema
 * @param campaignId owning campaign
 * @param lineage confirmed lineage and contract
 * @param candidateId immutable candidate selected for measurement
 * @param measurementEpochRef current measurement identity
 * @param sourceEvidenceRef confirmed loading evidence for the first batch, or the preceding batch's outcome revision
 * @param allocationPolicy confirmed category allocation policy
 * @param eligibleCategories confirmed category order for initial coverage
 * @param stoppedCategories categories stopped before this choice
 * @param categoryId selected category
 * @param purpose representative development purpose
 * @param batchNumber zero-based development batch number
 * @param position zero-based position within the batch
 */
public record InitialCategoryDecision(int schemaVersion, String campaignId, LineageKey lineage,
	String candidateId, EvidenceRef measurementEpochRef, EvidenceRef sourceEvidenceRef,
	DevelopmentAllocationPolicy allocationPolicy, List<String> eligibleCategories,
	List<String> stoppedCategories, String categoryId, EvidencePurpose purpose, int batchNumber, int position)
{
	/** Supported decision schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates one first-coverage choice's immutable identity and source revision.
	 *
	 * @param schemaVersion supported decision schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed lineage and contract
	 * @param candidateId immutable candidate selected for measurement
	 * @param measurementEpochRef current measurement identity
	 * @param sourceEvidenceRef confirmed loading evidence or preceding batch completion
	 * @param allocationPolicy confirmed category allocation policy
	 * @param eligibleCategories confirmed category order for initial coverage
	 * @param stoppedCategories categories stopped before this choice
	 * @param categoryId selected category
	 * @param purpose representative development purpose
	 * @param batchNumber zero-based development batch number
	 * @param position zero-based position within the batch
	 */
	public InitialCategoryDecision
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(sourceEvidenceRef, "sourceEvidenceRef");
		Objects.requireNonNull(allocationPolicy, "allocationPolicy");
		eligibleCategories = List.copyOf(eligibleCategories);
		stoppedCategories = List.copyOf(stoppedCategories);
		boolean validSource = batchNumber == 0 && sourceEvidenceRef.collection().equals("prompt-loading") ||
			batchNumber > 0 && sourceEvidenceRef.collection().equals("coverage-completions");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			candidateId == null || candidateId.isBlank() ||
			measurementEpochRef == null || !measurementEpochRef.collection().equals("measurement-epochs") ||
			eligibleCategories.isEmpty() || eligibleCategories.stream().anyMatch(value -> value == null || value.isBlank()) ||
			!stoppedCategories.isEmpty() || batchNumber < 0 || batchNumber >= eligibleCategories.size() ||
			!Objects.equals(categoryId, eligibleCategories.get(batchNumber)) ||
			!validSource ||
			purpose != EvidencePurpose.DEVELOPMENT || position != 0)
			throw new IllegalArgumentException("Initial development category decision is invalid");
	}
}
