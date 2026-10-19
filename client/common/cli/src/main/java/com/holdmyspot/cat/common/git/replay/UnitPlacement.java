package com.holdmyspot.cat.common.git.replay;

/**
 * One changed path's placement within a reviewer-authored source review.
 *
 * @param inventoryId the immutable changed-path identifier
 * @param affectedUnit the behavior, contract, test, configuration element, or other unit that changed
 * @param owningResponsibility the responsibility whose final commit needs this unit
 * @param destination the output that owns the responsibility
 * @param evidence the concrete predecessor, consumer, or prerequisite evidence for the placement
 */
public record UnitPlacement(String inventoryId, String affectedUnit, String owningResponsibility, String destination,
	String evidence)
{
}
