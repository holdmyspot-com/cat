package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable identity of one confirmed claim's measurement context.
 *
 * @param schemaVersion supported schema
 * @param campaignId owning campaign
 * @param lineageId confirmed lineage
 * @param contractRef complete confirmed contract
 * @param loadingEvidenceRef retained source-loading graph
 * @param predecessorRef previous measurement epoch when a known change starts this one
 * @param sourceChangeRef known change resolved by this epoch
 */
public record MeasurementEpoch(int schemaVersion, String campaignId, String lineageId,
	EvidenceRef contractRef, EvidenceRef loadingEvidenceRef, Optional<EvidenceRef> predecessorRef,
	Optional<EvidenceRef> sourceChangeRef)
{
	/** Supported measurement epoch schema. */
	public static final int SCHEMA_VERSION = 2;

	/** Validates the immutable claim and source references. */
	public MeasurementEpoch
	{
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			lineageId == null || lineageId.isBlank())
			throw new IllegalArgumentException("Measurement epoch identity is invalid");
		Objects.requireNonNull(contractRef, "contractRef");
		Objects.requireNonNull(loadingEvidenceRef, "loadingEvidenceRef");
		Objects.requireNonNull(predecessorRef, "predecessorRef");
		Objects.requireNonNull(sourceChangeRef, "sourceChangeRef");
		if (!contractRef.collection().equals("contracts") ||
			!loadingEvidenceRef.collection().equals("prompt-loading") ||
			predecessorRef.isPresent() != sourceChangeRef.isPresent() ||
			predecessorRef.stream().anyMatch(ref -> !ref.collection().equals("measurement-epochs")) ||
			sourceChangeRef.stream().anyMatch(ref -> !ref.collection().equals("measurement-changes")))
			throw new IllegalArgumentException("Measurement epoch has a foreign evidence collection");
	}
}
