package io.github.cowwoc.cat.common.optimizer.state;

/**
 * One locked snapshot of the currently confirmed campaign claim.
 *
 * @param campaignId campaign identity
 * @param activeLineageId active lineage identity
 * @param activeContractId active confirmed contract identity
 * @param transitionCounter observed campaign counter
 */
public record CurrentClaim(String campaignId, String activeLineageId, String activeContractId,
	long transitionCounter)
{
	/**
	 * Requires a complete confirmed claim.
	 */
	public CurrentClaim
	{
		if (campaignId == null || campaignId.isBlank() || activeLineageId == null || activeLineageId.isBlank() ||
			activeContractId == null || activeContractId.isBlank() || transitionCounter < 0)
			throw new IllegalArgumentException("Current claim is incomplete");
	}
}
