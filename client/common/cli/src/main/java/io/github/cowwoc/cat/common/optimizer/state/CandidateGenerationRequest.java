package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.Objects;

/**
 * A source-backed request to improve one failing category in a verified private worktree.
 *
 * @param schemaVersion supported request schema
 * @param campaignId owning campaign
 * @param lineage confirmed contract lineage
 * @param logicalCallId native call reserved by the generation intent
 * @param categoryId failing category to improve
 * @param incumbentCandidateId exact candidate being improved
 * @param roundRef development round that selected the category
 * @param sourceRevisionRef counted outcomes used by that round
 * @param measurementEpochRef comparable measurement conditions
 * @param confirmedRootsRef confirmed authority for editable paths
 * @param readinessRef verified private worktree before authoring
 * @param selectedProject selected optimization worktree that remains unchanged during authoring
 * @param privateProject private worktree where authoring may occur
 */
record CandidateGenerationRequest(int schemaVersion, String campaignId, LineageKey lineage,
	String logicalCallId, String categoryId, String incumbentCandidateId, EvidenceRef roundRef,
	EvidenceRef sourceRevisionRef, EvidenceRef measurementEpochRef, EvidenceRef confirmedRootsRef,
	EvidenceRef readinessRef, Path selectedProject, Path privateProject)
{
	/**
	 * The supported request schema.
	 */
	static final int SCHEMA_VERSION = 1;

	/**
	 * Checks the request identities and evidence collections.
	 *
	 * @param schemaVersion supported request schema
	 * @param campaignId owning campaign
	 * @param lineage confirmed contract lineage
	 * @param logicalCallId native call reserved by the generation intent
	 * @param categoryId failing category to improve
	 * @param incumbentCandidateId exact candidate being improved
	 * @param roundRef development round that selected the category
	 * @param sourceRevisionRef counted outcomes used by that round
	 * @param measurementEpochRef comparable measurement conditions
	 * @param confirmedRootsRef confirmed authority for editable paths
	 * @param readinessRef verified private worktree before authoring
	 * @param selectedProject selected optimization worktree that remains unchanged during authoring
	 * @param privateProject private worktree where authoring may occur
	 */
	CandidateGenerationRequest
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(roundRef, "roundRef");
		Objects.requireNonNull(sourceRevisionRef, "sourceRevisionRef");
		Objects.requireNonNull(measurementEpochRef, "measurementEpochRef");
		Objects.requireNonNull(confirmedRootsRef, "confirmedRootsRef");
		Objects.requireNonNull(readinessRef, "readinessRef");
		Objects.requireNonNull(selectedProject, "selectedProject");
		Objects.requireNonNull(privateProject, "privateProject");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			logicalCallId == null || logicalCallId.isBlank() || categoryId == null || categoryId.isBlank() ||
			incumbentCandidateId == null || !incumbentCandidateId.matches("[0-9a-f]{64}") ||
			!roundRef.collection().equals("development-round-starts") ||
			!sourceRevisionRef.collection().equals("development-evidence-revisions") ||
			!measurementEpochRef.collection().equals("measurement-epochs") ||
			!confirmedRootsRef.collection().equals("confirmed-editable-roots") ||
			!readinessRef.collection().equals("generation-attempt-readiness") ||
			!selectedProject.isAbsolute() || !selectedProject.equals(selectedProject.normalize()) ||
			!privateProject.isAbsolute() || !privateProject.equals(privateProject.normalize()) ||
			privateProject.equals(selectedProject))
			throw new IllegalArgumentException("Candidate generation request is invalid");
	}
}
