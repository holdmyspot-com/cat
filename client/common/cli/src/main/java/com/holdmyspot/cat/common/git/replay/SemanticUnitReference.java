package com.holdmyspot.cat.common.git.replay;

/**
 * One reviewer-declared semantic unit within an immutable changed path.
 *
 * @param inventoryId the manifest's immutable changed-path identifier
 * @param affectedUnit the concrete behavior, contract, test, or documentation unit in that path
 */
public record SemanticUnitReference(String inventoryId, String affectedUnit)
{
}
