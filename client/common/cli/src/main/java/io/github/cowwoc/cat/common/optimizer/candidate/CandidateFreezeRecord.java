package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Immutable binding of a candidate snapshot, editable roots, inventory, and known-source baseline.
 *
 * @param schemaVersion durable schema version
 * @param candidateSnapshotRef candidate snapshot identity
 * @param confirmedEditableRootsRef C4-confirmed edit authority
 * @param rootInventoryRef exact frozen root-path inventory
 * @param confirmedSourceBaselineRef immutable known-source baseline identity
 */
public record CandidateFreezeRecord(int schemaVersion, String candidateSnapshotRef,
	EvidenceRef confirmedEditableRootsRef, EvidenceRef rootInventoryRef, String confirmedSourceBaselineRef)
{
	/** The only supported freeze schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Validates immutable references. */
	public CandidateFreezeRecord
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported candidate freeze schema: " + schemaVersion);
		if (!PromptSnapshot.isSha256(candidateSnapshotRef) || !PromptSnapshot.isSha256(confirmedSourceBaselineRef))
			throw new IllegalArgumentException("Candidate freeze snapshot and baseline refs must be SHA-256 ids");
		Objects.requireNonNull(confirmedEditableRootsRef, "confirmedEditableRootsRef");
		Objects.requireNonNull(rootInventoryRef, "rootInventoryRef");
		if (!confirmedEditableRootsRef.collection().equals("confirmed-editable-roots") ||
			!rootInventoryRef.collection().equals("root-inventories"))
			throw new IllegalArgumentException("Candidate freeze references have incorrect collections");
	}
}
