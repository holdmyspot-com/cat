package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import java.util.List;
import java.util.Objects;

/**
 * Persists one preconfirmation inspection request with its concrete project context.
 *
 * @param schemaVersion the request schema
 * @param campaignId the campaign that owns the request
 * @param logicalCallId the stable inspection call identity
 * @param context the isolated project snapshot context
 * @param turns the single inspection instruction
 * @param modelProfile the campaign's selected optimization model
 */
public record InspectionRunRequest(int schemaVersion, String campaignId, String logicalCallId,
	ProjectRunContext context, List<String> turns, ModelProfile modelProfile)
{
	/**
	 * Validates the complete inspection run request.
	 *
	 * @param schemaVersion the request schema
	 * @param campaignId the campaign that owns the request
	 * @param logicalCallId the stable inspection call identity
	 * @param context the isolated project snapshot context
	 * @param turns the single inspection instruction
	 * @param modelProfile the campaign's selected optimization model
	 */
	public InspectionRunRequest
	{
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(turns, "turns");
		Objects.requireNonNull(modelProfile, "modelProfile");
		if (schemaVersion != 1 || campaignId == null || campaignId.isBlank() ||
			logicalCallId == null || logicalCallId.isBlank() ||
			context.mode() != ProjectWorkspaceMode.ISOLATED_COPY || turns.size() != 1 ||
			turns.getFirst() == null || turns.getFirst().isBlank())
			throw new IllegalArgumentException("Inspection run request is invalid");
		turns = List.copyOf(turns);
	}

	/**
	 * Restores the native runner's fixed validation and single-session policy.
	 *
	 * @return the executable native request
	 */
	public NestedRunRequest asNested()
	{
		return new NestedRunRequest(logicalCallId, EvidencePurpose.VALIDATION, context, turns,
			SessionMode.SINGLE_TURN, modelProfile, List.of());
	}
}
