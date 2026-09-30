package io.github.cowwoc.cat.common.optimizer.state;

import java.util.Objects;

/**
 * Result of locked campaign creation or discovery.
 *
 * @param status whether this call created the campaign or found one requiring user action
 * @param handle current durable campaign handle
 */
public record CampaignRegistration(Status status, CampaignHandle handle)
{
	/**
	 * Closed registration outcomes.
	 */
	public enum Status
	{
		CREATED,
		EXISTING_CAMPAIGN_ACTION_REQUIRED
	}

	/**
	 * Requires a complete registration result.
	 */
	public CampaignRegistration
	{
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(handle, "handle");
	}
}
