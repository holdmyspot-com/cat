package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Durable authority and selected-worktree identity fixed before creating one private generation attempt.
 *
 * @param schemaVersion supported record schema
 * @param campaignId owning campaign
 * @param lineage confirmed contract lineage
 * @param roundRef counted development round requiring an attempt
 * @param categoryId failing category selected from that round
 * @param logicalCallId native generation call to bind later
 * @param incumbentCandidateId exact candidate being improved
 * @param measurementEpochRef comparable measurement conditions
 * @param confirmedRootsRef confirmed edit authority for the selected worktree
 * @param selectedInventoryRef complete selected-worktree state before private materialization
 * @param selectedProject pinned selected optimization worktree
 * @param privateProject reserved private Git worktree for this attempt
 */
record GenerationAttemptIntent(int schemaVersion, String campaignId, LineageKey lineage,
	EvidenceRef roundRef, String categoryId, String logicalCallId, String incumbentCandidateId,
	EvidenceRef measurementEpochRef, EvidenceRef confirmedRootsRef, EvidenceRef selectedInventoryRef,
	Path selectedProject, Path privateProject)
{
	/**
	 * The supported generation-intent schema.
	 */
	static final int SCHEMA_VERSION = 1;

	/**
	 * Validates the generation attempt's immutable identities before publication or replay.
	 *
	 * @param schemaVersion supported record schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed contract lineage
	 * @param roundRef counted development round requiring an attempt
	 * @param categoryId failing category selected from that round
	 * @param logicalCallId generation call identity
	 * @param incumbentCandidateId exact candidate being improved
	 * @param measurementEpochRef comparable measurement conditions
	 * @param confirmedRootsRef confirmed edit authority for the selected worktree
	 * @param selectedInventoryRef complete selected-worktree state before private materialization
	 * @param selectedProject pinned selected optimization worktree
	 * @param privateProject reserved private Git worktree for this attempt
	 */
	GenerationAttemptIntent
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(roundRef, "roundRef");
		Objects.requireNonNull(measurementEpochRef, "measurementEpochRef");
		Objects.requireNonNull(confirmedRootsRef, "confirmedRootsRef");
		Objects.requireNonNull(selectedInventoryRef, "selectedInventoryRef");
		Objects.requireNonNull(selectedProject, "selectedProject");
		Objects.requireNonNull(privateProject, "privateProject");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			categoryId == null || categoryId.isBlank() || logicalCallId == null || logicalCallId.isBlank() ||
			incumbentCandidateId == null || !incumbentCandidateId.matches("[0-9a-f]{64}") ||
			!roundRef.collection().equals("development-round-starts") ||
			!measurementEpochRef.collection().equals("measurement-epochs") ||
			!confirmedRootsRef.collection().equals("confirmed-editable-roots") ||
			!selectedInventoryRef.collection().equals("generation-selected-inventories") ||
			!selectedProject.isAbsolute() || !selectedProject.equals(selectedProject.normalize()) ||
			!privateProject.isAbsolute() || !privateProject.equals(privateProject.normalize()) ||
			privateProject.equals(selectedProject) ||
			!Objects.equals(privateProject.getParent(), selectedProject.getParent()))
			throw new IllegalArgumentException("Generation attempt intent is invalid");
	}
}
