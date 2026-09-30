package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.PrivateGenerationContext;
import java.util.List;
import java.util.Objects;

/**
 * Durable native request to improve one failing category in its private worktree.
 *
 * @param schemaVersion supported request schema
 * @param campaignId owning campaign
 * @param semanticRequestRef indexed generation request
 * @param logicalCallId stable candidate-generation call identity
 * @param context verified private worktree and confirmed edit authority
 * @param turn instructions and counted development evidence for authoring
 * @param modelProfile campaign's selected optimization model
 */
public record CandidateGenerationRunRequest(int schemaVersion, String campaignId,
	EvidenceRef semanticRequestRef, String logicalCallId, PrivateGenerationContext context, String turn,
	ModelProfile modelProfile)
{
	/** Supported native request schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the native request identity and one-turn execution shape.
	 *
	 * @param schemaVersion supported request schema
	 * @param campaignId owning campaign
	 * @param semanticRequestRef indexed generation request
	 * @param logicalCallId stable candidate-generation call identity
	 * @param context verified private worktree and confirmed edit authority
	 * @param turn instructions and counted development evidence for authoring
	 * @param modelProfile campaign's selected optimization model
	 */
	public CandidateGenerationRunRequest
	{
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(modelProfile, "modelProfile");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!semanticRequestRef.collection().equals("candidate-generation-requests") ||
			logicalCallId == null || logicalCallId.isBlank() || turn == null || turn.isBlank() ||
			!context.generationRequestRef().equals(semanticRequestRef))
			throw new IllegalArgumentException("Candidate generation native request is invalid");
		new NestedRunRequest(logicalCallId, EvidencePurpose.DEVELOPMENT, context,
			List.of(turn), SessionMode.SINGLE_TURN, modelProfile, List.of());
	}

	/**
	 * Restores the native authoring call from the retained request.
	 *
	 * @return executable private-worktree request
	 */
	public NestedRunRequest asNested()
	{
		return new NestedRunRequest(logicalCallId, EvidencePurpose.DEVELOPMENT, context,
			List.of(turn), SessionMode.SINGLE_TURN, modelProfile, List.of());
	}
}
