package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Binds one lineage producer's semantic request to the native request before native dispatch.
 *
 * @param schemaVersion exactly one
 * @param kind the lineage producer
 * @param campaignId the pinned campaign
 * @param lineage the active confirmed contract lineage
 * @param logicalCallId the native logical call
 * @param semanticRequestRef the producer's immutable request
 * @param runRequestRef the native request claimed by the scheduler
 */
public record PreparedLineageCall(int schemaVersion, ProducerAttemptKind kind, String campaignId,
	LineageKey lineage, String logicalCallId, EvidenceRef semanticRequestRef,
	EvidenceRef runRequestRef)
{
	/**
	 * Validates the fixed binding identity and request collections.
	 *
	 * @throws NullPointerException if a required value is null
	 * @throws IllegalArgumentException if a field or collection is invalid
	 */
	public PreparedLineageCall
	{
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		if (schemaVersion != 1 || campaignId == null || campaignId.isBlank() ||
			logicalCallId == null || logicalCallId.isBlank() ||
			!runRequestRef.collection().equals("run-requests"))
			throw new IllegalArgumentException("Prepared lineage call identity is invalid");
		String requiredCollection = switch (kind)
		{
			case GENERATOR_VALIDATION -> "generator-validation-requests";
			case CANDIDATE -> "candidate-generation-requests";
			case EVALUATOR -> "evaluator-requests";
			case TRIAL -> "trial-assignments";
			default -> throw new IllegalArgumentException("Prepared lineage call kind is invalid");
		};
		if (!semanticRequestRef.collection().equals(requiredCollection))
			throw new IllegalArgumentException("Prepared lineage call semantic request collection is invalid");
	}
}
