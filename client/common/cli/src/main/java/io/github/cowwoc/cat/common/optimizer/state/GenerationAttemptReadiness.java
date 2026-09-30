package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Verified private worktree state produced from one retained generation intent.
 *
 * @param schemaVersion supported record schema
 * @param campaignId owning campaign
 * @param intentRef generation intent that reserved the private worktree
 * @param privateInventoryRef complete private-worktree inventory before authoring
 */
record GenerationAttemptReadiness(int schemaVersion, String campaignId, EvidenceRef intentRef,
	EvidenceRef privateInventoryRef)
{
	/**
	 * The supported readiness schema.
	 */
	static final int SCHEMA_VERSION = 1;

	/**
	 * Checks that the record names an intent and a captured private worktree.
	 *
	 * @param schemaVersion supported record schema
	 * @param campaignId owning campaign
	 * @param intentRef generation intent that reserved the private worktree
	 * @param privateInventoryRef complete private-worktree inventory before authoring
	 */
	GenerationAttemptReadiness
	{
		Objects.requireNonNull(intentRef, "intentRef");
		Objects.requireNonNull(privateInventoryRef, "privateInventoryRef");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!intentRef.collection().equals("generation-attempt-intents") ||
			!privateInventoryRef.collection().equals("generation-private-inventories"))
			throw new IllegalArgumentException("Generation attempt readiness is invalid");
	}
}
