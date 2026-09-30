package io.github.cowwoc.cat.common.optimizer.loading;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import io.github.cowwoc.cat.common.optimizer.contract.PromptSource;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationCoverage;
import io.github.cowwoc.cat.common.optimizer.runner.ObservationDomain;
import io.github.cowwoc.cat.common.optimizer.runner.RuntimePromptSource;
import java.util.List;
import java.util.Objects;

/**
 * Retains requested and observed project sources from an adopted inspection separately from native path observations.
 *
 * @param schemaVersion evidence schema
 * @param campaignId campaign owning the inspection
 * @param inspectionResultRef adopted inspection result
 * @param selectedSource requested source and its initial captured byte identity
 * @param sources selected source followed by distinct observed sources with initial captured byte identities
 * @param pathObservations native path observations, without a consumed-byte claim
 * @param coverage native prompt-source observation coverage
 * @param proposedEditableRoot suggested resolved-file edit root for contract review
 */
public record InspectedSourceEvidence(int schemaVersion, String campaignId, EvidenceRef inspectionResultRef,
	PromptSource selectedSource, List<PromptSource> sources, List<RuntimePromptSource> pathObservations,
	ObservationCoverage coverage,
	EditableRoot proposedEditableRoot)
{
	/** Supported inspected-source evidence schema. */
	public static final int SCHEMA_VERSION = 3;

	/**
	 * Validates the selected source and observation boundary.
	 */
	public InspectedSourceEvidence
	{
		Objects.requireNonNull(inspectionResultRef, "inspectionResultRef");
		Objects.requireNonNull(selectedSource, "selectedSource");
		sources = List.copyOf(sources);
		pathObservations = List.copyOf(pathObservations);
		Objects.requireNonNull(coverage, "coverage");
		Objects.requireNonNull(proposedEditableRoot, "proposedEditableRoot");
		if (schemaVersion != SCHEMA_VERSION || campaignId == null || campaignId.isBlank() ||
			!inspectionResultRef.collection().equals("contract-inspection-results") ||
			sources.isEmpty() || !sources.getFirst().equals(selectedSource) ||
			sources.stream().map(PromptSource::path).distinct().count() != sources.size() ||
			coverage.domain() != ObservationDomain.PROMPT_SOURCE ||
			proposedEditableRoot.kind() != EditableRoot.Kind.FILE ||
			!proposedEditableRoot.path().equals(selectedSource.resolvedSourcePath()))
			throw new IllegalArgumentException("Inspected source evidence is invalid");
	}
}
