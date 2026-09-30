package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.trial.SampledTrialInstance;
import java.time.Instant;
import java.util.Objects;

/**
 * Durable selection of one concrete instance and candidate before a native trial starts.
 *
 * @param schemaVersion supported assignment schema
 * @param campaignId exact campaign identity
 * @param lineage confirmed lineage and contract
 * @param measurementEpochRef measurement identity selected before execution
 * @param categoryDecisionRef published category choice that authorized the draw
 * @param logicalTrialId stable trial identity
 * @param ordinal zero-based assignment number within the confirmed category
 * @param candidateId immutable starting candidate snapshot
 * @param instance concrete selected test and incidental values
 * @param assignedAt time the trial was assigned
 */
public record TrialAssignment(int schemaVersion, String campaignId, LineageKey lineage,
	EvidenceRef measurementEpochRef, EvidenceRef categoryDecisionRef, String logicalTrialId, int ordinal,
	String candidateId,
	SampledTrialInstance instance, Instant assignedAt)
{
	/** Supported assignment schema. */
	public static final int SCHEMA_VERSION = 4;

	/**
	 * Requires one confirmed development draw bound to a candidate snapshot.
	 *
	 * @param schemaVersion supported assignment schema
	 * @param campaignId owning campaign identity
	 * @param lineage confirmed lineage and contract
	 * @param measurementEpochRef measurement identity selected before execution
	 * @param categoryDecisionRef published category choice that authorized the draw
	 * @param logicalTrialId stable trial identity
	 * @param ordinal zero-based assignment number within the confirmed category
	 * @param candidateId immutable starting candidate snapshot
	 * @param instance concrete development instance
	 * @param assignedAt time the assignment was published
	 */
	public TrialAssignment
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(measurementEpochRef, "measurementEpochRef");
		Objects.requireNonNull(categoryDecisionRef, "categoryDecisionRef");
		Objects.requireNonNull(instance, "instance");
		Objects.requireNonNull(assignedAt, "assignedAt");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() || ordinal < 0 ||
			logicalTrialId == null || logicalTrialId.isBlank() ||
			candidateId == null || candidateId.isBlank() ||
			!measurementEpochRef.collection().equals("measurement-epochs") ||
			!categoryDecisionRef.collection().equals("category-decisions") &&
				!categoryDecisionRef.collection().equals("development-category-decisions") ||
			instance.purpose() != EvidencePurpose.DEVELOPMENT ||
			!instance.contractId().equals(lineage.contractId()))
			throw new IllegalArgumentException("Development trial assignment identity is invalid");
	}
}
