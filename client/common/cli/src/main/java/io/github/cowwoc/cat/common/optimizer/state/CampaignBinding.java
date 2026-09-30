package io.github.cowwoc.cat.common.optimizer.state;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Exact campaign identity and pinned real directory used by state operations.
 *
 * @param campaignId campaign identity
 * @param realDirectory pinned real directory
 */
public record CampaignBinding(String campaignId, Path realDirectory)
{
	/**
	 * Validates an exact campaign binding.
	 */
	public CampaignBinding
	{
		if (campaignId == null || campaignId.isBlank())
			throw new IllegalArgumentException("Campaign id must be non-blank");
		Objects.requireNonNull(realDirectory, "realDirectory");
		if (!realDirectory.isAbsolute() || !realDirectory.normalize().equals(realDirectory))
			throw new IllegalArgumentException("Campaign directory must be absolute and normalized");
	}
}
