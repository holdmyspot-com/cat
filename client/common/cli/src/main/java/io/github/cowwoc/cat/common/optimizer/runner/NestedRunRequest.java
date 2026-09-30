package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidencePurpose;
import io.github.cowwoc.cat.common.optimizer.api.HostToolRequirement;
import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import io.github.cowwoc.cat.common.optimizer.api.SessionMode;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Specifies one logical native call before any session is started.
  *
 * @param logicalCallId the recorded logicalCallId
 * @param purpose the recorded purpose
 * @param context the recorded context
 * @param turns the recorded turns
 * @param sessionMode the recorded sessionMode
 * @param modelProfile the recorded modelProfile
 * @param requiredHostTools the recorded requiredHostTools
 */
public record NestedRunRequest(String logicalCallId, EvidencePurpose purpose, ExecutionContext context,
    List<String> turns, SessionMode sessionMode, ModelProfile modelProfile,
    List<HostToolRequirement> requiredHostTools)
{
    /**
     * Validates purpose, isolation, turns and host-tool identity.
     */
    public NestedRunRequest
    {
        Objects.requireNonNull(logicalCallId, "logicalCallId");
        Objects.requireNonNull(purpose, "purpose");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sessionMode, "sessionMode");
        Objects.requireNonNull(modelProfile, "modelProfile");
        if (logicalCallId.isBlank())
            throw new IllegalArgumentException("logicalCallId must not be blank");
        turns = List.copyOf(turns);
        requiredHostTools = List.copyOf(requiredHostTools);
        if (turns.isEmpty() || sessionMode == SessionMode.SINGLE_TURN && turns.size() != 1 ||
            sessionMode == SessionMode.MULTI_TURN && turns.size() < 2)
            throw new IllegalArgumentException("turn count does not match session mode");
        var toolIds = new HashSet<String>();
        for (HostToolRequirement tool : requiredHostTools)
        {
            if (!toolIds.add(tool.toolId()))
                throw new IllegalArgumentException("host tool IDs must be unique");
        }
        if (context instanceof ProjectRunContext project)
        {
            if (project.mode() == ProjectWorkspaceMode.ACTIVE_WORKTREE && purpose != EvidencePurpose.DEVELOPMENT)
                throw new IllegalArgumentException("active worktree requires DEVELOPMENT");
        }
        else if (context instanceof PrivateGenerationContext && purpose != EvidencePurpose.DEVELOPMENT)
            throw new IllegalArgumentException("private generation requires DEVELOPMENT");
        else if (context instanceof SterileWorkspaceContext sterile)
        {
            if (!requiredHostTools.isEmpty())
                throw new IllegalArgumentException("sterile calls cannot require host tools");
            if (sterile.kind() != SterileWorkspaceKind.EVALUATOR && purpose != EvidencePurpose.VALIDATION ||
                sterile.kind() == SterileWorkspaceKind.EVALUATOR && purpose == EvidencePurpose.VALIDATION)
                throw new IllegalArgumentException("sterile workspace kind does not match purpose");
        }
    }
}
