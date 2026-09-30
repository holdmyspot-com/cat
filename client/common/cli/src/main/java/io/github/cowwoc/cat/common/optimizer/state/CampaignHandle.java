package io.github.cowwoc.cat.common.optimizer.state;

import java.nio.file.Path;
import java.time.Instant;

/**
 * Reopenable summary of a durable campaign discovered by {@link CampaignLocator}.
 *
 * @param campaignId durable campaign identity
 * @param campaignDirectory campaign state directory
 * @param status current lifecycle status
 * @param activeLineageId active lineage, or null before confirmation
 * @param transitionCounter current transition counter
 * @param updatedAt time the campaign state file was last persisted
 * @param resumable whether an explicit resume can continue the recorded closed phase
 */
public record CampaignHandle(String campaignId, Path campaignDirectory, CampaignStatus status, String activeLineageId,
	long transitionCounter, Instant updatedAt, boolean resumable)
{
	/** Validates the portable index summary. */
	public CampaignHandle
	{
		if (campaignId == null || campaignId.isBlank() || campaignDirectory == null || status == null ||
			updatedAt == null ||
			transitionCounter < 0)
		{
			throw new IllegalArgumentException("Campaign handle fields are invalid");
		}
		campaignDirectory = campaignDirectory.toAbsolutePath().normalize();
	}
}
