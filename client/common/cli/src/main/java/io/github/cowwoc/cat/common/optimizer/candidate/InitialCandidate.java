package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;

/**
 * Immutable first candidate derived from the retained source capture without an authoring call.
 *
 * @param schemaVersion durable schema version
 * @param campaignId owning campaign
 * @param lineageId owning confirmed lineage
 * @param contractId immutable confirmed contract
 * @param sourceAuthorityRef indexed roots and source baseline
 * @param sourceCaptureRef retained initial project capture
 * @param candidateSnapshotId exact selected prompt-system snapshot
 */
public record InitialCandidate(int schemaVersion, String campaignId, String lineageId, String contractId,
	EvidenceRef sourceAuthorityRef, EvidenceRef sourceCaptureRef, String candidateSnapshotId)
{
	/** The only supported initial-candidate schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates the candidate's durable identity and reference kinds. */
	public InitialCandidate
	{
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!PromptSnapshot.isSha256(lineageId) || !PromptSnapshot.isSha256(contractId) ||
			!PromptSnapshot.isSha256(candidateSnapshotId))
			throw new IllegalArgumentException("Initial candidate identity is invalid");
		if (sourceAuthorityRef == null || !sourceAuthorityRef.collection().equals("confirmed-editable-roots") ||
			sourceCaptureRef == null || !sourceCaptureRef.collection().equals("contract-source-captures"))
			throw new IllegalArgumentException("Initial candidate evidence references are invalid");
	}
}
