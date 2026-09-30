package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.api.HostToolRequirement;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import io.github.cowwoc.cat.common.optimizer.runner.NestedRunRequest;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectRunContext;
import io.github.cowwoc.cat.common.optimizer.runner.ProjectWorkspaceMode;
import java.util.List;
import java.util.Objects;

/**
 * Durable isolated native request for an assigned initial development trial.
 *
 * @param schemaVersion supported request schema
 * @param campaignId owning campaign
 * @param assignmentRef retained trial assignment
 * @param logicalCallId stable call identity
 * @param context exact isolated starting project
 * @param turns concrete assigned test turns
 * @param sessionMode confirmed turn-sharing policy
 * @param modelProfile selected trial model
 * @param requiredHostTools confirmed command-line tool requirements
 */
public record InitialTrialRunRequest(int schemaVersion, String campaignId, EvidenceRef assignmentRef,
	String logicalCallId, ProjectRunContext context, List<String> turns, SessionMode sessionMode,
	ModelProfile modelProfile, List<HostToolRequirement> requiredHostTools)
{
	/** Supported initial trial request schema. */
	public static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the durable assignment and executable request shape.
	 *
	 * @param schemaVersion supported request schema
	 * @param campaignId owning campaign
	 * @param assignmentRef retained trial assignment
	 * @param logicalCallId stable call identity
	 * @param context exact isolated starting project
	 * @param turns concrete assigned test turns
	 * @param sessionMode confirmed turn-sharing policy
	 * @param modelProfile selected trial model
	 * @param requiredHostTools confirmed command-line tool requirements
	 */
	public InitialTrialRunRequest
	{
		Objects.requireNonNull(assignmentRef, "assignmentRef");
		Objects.requireNonNull(context, "context");
		Objects.requireNonNull(sessionMode, "sessionMode");
		Objects.requireNonNull(modelProfile, "modelProfile");
		turns = List.copyOf(turns);
		requiredHostTools = List.copyOf(requiredHostTools);
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!assignmentRef.collection().equals("trial-assignments") ||
			logicalCallId == null || logicalCallId.isBlank() ||
			context.mode() != ProjectWorkspaceMode.ISOLATED_COPY)
			throw new IllegalArgumentException("Initial trial request identity is invalid");
		new NestedRunRequest(logicalCallId, EvidencePurpose.DEVELOPMENT, context, turns, sessionMode,
			modelProfile, requiredHostTools);
	}

	/**
	 * Restores the runner's executable request from its retained assignment.
	 *
	 * @return executable native call
	 */
	public NestedRunRequest asNested()
	{
		return new NestedRunRequest(logicalCallId, EvidencePurpose.DEVELOPMENT, context, turns, sessionMode,
			modelProfile, requiredHostTools);
	}
}
