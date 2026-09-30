package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.ModelProfile;
import java.util.Objects;

/**
 * Immutable inputs for first publication of a campaign under a lookup key.
 *
 * @param campaignId stable campaign identity
 * @param optimizationProfile immutable model profile
 */
public record CampaignCreationRequest(String campaignId, ModelProfile optimizationProfile)
{
	/**
	 * Validates initial campaign identity and profile.
	 */
	public CampaignCreationRequest
	{
		if (campaignId == null || campaignId.isBlank() || !campaignId.matches("[A-Za-z0-9-]+"))
			throw new IllegalArgumentException("Campaign id must be a non-blank pathname-safe identity");
		Objects.requireNonNull(optimizationProfile, "optimizationProfile");
	}
}
