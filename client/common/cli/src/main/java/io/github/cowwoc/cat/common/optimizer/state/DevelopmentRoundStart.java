package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.contract.ComparisonPolicy;
import io.github.cowwoc.cat.common.optimizer.contract.DevelopmentRoundPolicy;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * The incumbent and failing categories fixed when an initial development round begins.
 *
 * @param schemaVersion supported record schema
 * @param campaignId owning campaign
 * @param lineage confirmed contract lineage
 * @param incumbentCandidateId candidate being improved
 * @param measurementEpochRef comparable measurement conditions
 * @param sourceRevisionRef counted outcomes used to select the categories
 * @param roundPolicy confirmed limits on diagnosis and generation
 * @param comparisonPolicy confirmed stronger-evidence rule
 * @param failingCategories confirmed-order categories requiring attempts
 */
record DevelopmentRoundStart(int schemaVersion, String campaignId, LineageKey lineage,
	String incumbentCandidateId, EvidenceRef measurementEpochRef, EvidenceRef sourceRevisionRef,
	DevelopmentRoundPolicy roundPolicy, ComparisonPolicy comparisonPolicy, List<String> failingCategories)
{
	/** Supported record schema. */
	static final int SCHEMA_VERSION = 1;

	/**
	 * Copies the fixed category set and checks the retained source identity.
	 *
	 * @param schemaVersion supported record schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed contract lineage
	 * @param incumbentCandidateId candidate being improved
	 * @param measurementEpochRef comparable measurement conditions
	 * @param sourceRevisionRef counted outcomes used to select the categories
	 * @param roundPolicy confirmed limits on diagnosis and generation
	 * @param comparisonPolicy confirmed stronger-evidence rule
	 * @param failingCategories confirmed-order categories requiring attempts
	 */
	DevelopmentRoundStart
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(roundPolicy, "roundPolicy");
		Objects.requireNonNull(comparisonPolicy, "comparisonPolicy");
		failingCategories = List.copyOf(failingCategories);
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			incumbentCandidateId == null || incumbentCandidateId.isBlank() ||
			measurementEpochRef == null || !measurementEpochRef.collection().equals("measurement-epochs") ||
			sourceRevisionRef == null ||
			!sourceRevisionRef.collection().equals("development-evidence-revisions") ||
			failingCategories.isEmpty() || failingCategories.stream().anyMatch(String::isBlank) ||
			new HashSet<>(failingCategories).size() != failingCategories.size())
			throw new IllegalArgumentException("Initial development round is invalid");
	}
}
