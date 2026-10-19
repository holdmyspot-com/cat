package com.holdmyspot.cat.common.git.replay;

/**
 * One concrete semantic unit considered during candidate discovery.
 *
 * @param inventoryId the immutable changed artifact
 * @param affectedUnit the behavior, contract, test, or documentation unit in that artifact
 */
public record CandidateUniverseUnit(String inventoryId, String affectedUnit)
{
}
