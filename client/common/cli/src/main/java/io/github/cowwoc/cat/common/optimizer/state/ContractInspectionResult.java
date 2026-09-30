package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Retains the native completion adopted for one contract inspection attempt.
 *
 * @param schemaVersion result schema
 * @param campaignId owning campaign
 * @param ownerRef indexed inspection owner
 * @param attemptRef indexed inspection attempt
 * @param sourceCaptureRef the selected source relationship and retained bytes used to prepare the inspection
 * @param completionRef indexed native completion
 * @param selectedSourceProposalRef the selected source's proposed edit root after native success, or empty on failure
 */
public record ContractInspectionResult(int schemaVersion, String campaignId, EvidenceRef ownerRef,
	EvidenceRef attemptRef, EvidenceRef sourceCaptureRef, EvidenceRef completionRef,
	Optional<EvidenceRef> selectedSourceProposalRef)
{
	/** Validates the result's immutable evidence collections. */
	public ContractInspectionResult
	{
		Objects.requireNonNull(ownerRef, "ownerRef");
		Objects.requireNonNull(attemptRef, "attemptRef");
		Objects.requireNonNull(sourceCaptureRef, "sourceCaptureRef");
		Objects.requireNonNull(completionRef, "completionRef");
		Objects.requireNonNull(selectedSourceProposalRef, "selectedSourceProposalRef");
		if (schemaVersion != 3 || campaignId == null || campaignId.isBlank() ||
			!ownerRef.collection().equals("contract-preparation-owners") ||
			!attemptRef.collection().equals("contract-inspection-attempts") ||
			!sourceCaptureRef.collection().equals("contract-source-captures") ||
			!completionRef.collection().equals("native-run-completions") ||
			selectedSourceProposalRef.isPresent() && !selectedSourceProposalRef.orElseThrow().collection().equals(
				"selected-source-edit-proposals"))
			throw new IllegalArgumentException("Contract inspection result identity is invalid");
	}
}
