package io.github.cowwoc.cat.common.optimizer.runner;

/**
 * Identifies the project or sterile workspace used by one native call.
 */
public sealed interface ExecutionContext permits PrivateGenerationContext, ProjectRunContext, SterileWorkspaceContext
{
}
