package io.github.cowwoc.cat.common.optimizer.state;

import java.util.Objects;

/**
 * Latest durable confirmed claim for a canonical project and target key.
 *
 * @param key canonical project and target key
 * @param campaignId owning campaign identity
 * @param lineageId confirmed lineage identity
 * @param contractId confirmed contract identity
 * @param confirmationCounter counter of the committing transition
 * @param confirmationSequence monotone per-key claim version
 */
public record LatestConfirmedClaim(CampaignLookupKey key, String campaignId, String lineageId,
	String contractId, long confirmationCounter, long confirmationSequence)
{
	/**
	 * Requires a complete, positive confirmed claim.
	 */
	public LatestConfirmedClaim
	{
		Objects.requireNonNull(key, "key");
		if (campaignId == null || campaignId.isBlank() || lineageId == null || lineageId.isBlank() ||
			contractId == null || contractId.isBlank() || confirmationCounter < 1 || confirmationSequence < 1)
			throw new IllegalArgumentException("Latest confirmed claim is incomplete");
	}
}
