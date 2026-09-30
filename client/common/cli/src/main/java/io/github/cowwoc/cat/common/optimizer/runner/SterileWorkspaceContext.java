package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;

/**
 * Selects a sterile workspace through its immutable manifest.
 *
 * @param kind the purpose of the sterile workspace
 * @param manifestRef the immutable sterile workspace manifest
 */
public record SterileWorkspaceContext(SterileWorkspaceKind kind, EvidenceRef manifestRef)
	implements ExecutionContext
{
	/**
	 * operation at the native runner boundary.
	 */
	public SterileWorkspaceContext
	{
		java.util.Objects.requireNonNull(kind, "kind");
		java.util.Objects.requireNonNull(manifestRef, "manifestRef");
	}
}
