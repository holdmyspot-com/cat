package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceContext;
import io.github.cowwoc.cat.common.optimizer.runner.SterileWorkspaceKind;
import java.util.List;
import java.util.Objects;

/**
 * Durable native request that reads frozen evaluator authority and inert trial evidence.
 *
 * @param schemaVersion supported request schema
 * @param campaignId owning campaign
 * @param semanticRequestRef immutable evaluation request
 * @param logicalCallId stable evaluator call identity
 * @param context immutable sterile workspace manifest
 * @param turn instruction to judge the retained trial
 * @param modelProfile frozen evaluator model and reasoning profile
 */
public record InitialEvaluatorRunRequest(int schemaVersion, String campaignId, EvidenceRef semanticRequestRef,
	String logicalCallId, SterileWorkspaceContext context, String turn, ModelProfile modelProfile)
{
	/** Supported native request schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the executable evaluator request.
	 *
	 * @param schemaVersion supported request schema
	 * @param campaignId owning campaign
	 * @param semanticRequestRef immutable evaluation request
	 * @param logicalCallId stable evaluator call identity
	 * @param context immutable sterile workspace manifest
	 * @param turn instruction to judge the retained trial
	 * @param modelProfile frozen evaluator model and reasoning profile
	 */
	public InitialEvaluatorRunRequest
	{
		Objects.requireNonNull(semanticRequestRef, "semanticRequestRef");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(modelProfile, "modelProfile");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!semanticRequestRef.collection().equals("evaluator-requests") ||
			logicalCallId == null || logicalCallId.isBlank() || turn == null || turn.isBlank() ||
			context.kind() != SterileWorkspaceKind.EVALUATOR)
			throw new IllegalArgumentException("Initial evaluator native request identity is invalid");
		new NestedRunRequest(logicalCallId, EvidencePurpose.DEVELOPMENT, context,
			List.of(turn), SessionMode.SINGLE_TURN, modelProfile, List.of());
	}

	/**
	 * Restores the one-turn native evaluator call.
	 *
	 * @return executable native request
	 */
	public NestedRunRequest asNested()
	{
		return new NestedRunRequest(logicalCallId, EvidencePurpose.DEVELOPMENT, context,
			List.of(turn), SessionMode.SINGLE_TURN, modelProfile, List.of());
	}
}
