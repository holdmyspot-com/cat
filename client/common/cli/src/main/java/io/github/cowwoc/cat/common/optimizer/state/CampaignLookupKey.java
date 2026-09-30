package io.github.cowwoc.cat.common.optimizer.state;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Canonical project and target identity used to discover durable campaigns.
 *
 * @param canonicalProjectRoot canonical existing project root
 * @param normalizedTargetRelativePath normalized target path relative to the project root
 */
public record CampaignLookupKey(Path canonicalProjectRoot, Path normalizedTargetRelativePath)
{
	/** Normalizes the paths and resolves project-root aliases. */
	public CampaignLookupKey
	{
		if (canonicalProjectRoot == null || normalizedTargetRelativePath == null)
			throw new NullPointerException("Campaign lookup paths must not be null");
		try
		{
			canonicalProjectRoot = canonicalProjectRoot.toRealPath();
		}
		catch (IOException e)
		{
			throw new IllegalArgumentException("Project root cannot be canonicalized: " + canonicalProjectRoot, e);
		}
		normalizedTargetRelativePath = normalizedTargetRelativePath.normalize();
		if (normalizedTargetRelativePath.isAbsolute() || normalizedTargetRelativePath.startsWith("..") ||
			normalizedTargetRelativePath.toString().isEmpty())
		{
			throw new IllegalArgumentException("Target path must be a normalized path below the project root");
		}
	}
}
