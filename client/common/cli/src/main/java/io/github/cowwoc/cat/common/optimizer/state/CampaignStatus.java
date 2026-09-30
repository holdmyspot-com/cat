package io.github.cowwoc.cat.common.optimizer.state;

/**
 * The durable lifecycle phases of one optimizer campaign.
 */
public enum CampaignStatus
{
	AWAITING_CONTRACT_CONFIRMATION,
	DEVELOPING,
	FORMAL_TESTING,
	CERTIFIED,
	NOT_CERTIFIED_PLATEAU,
	INTERRUPTED,
	CANCELLED
}
