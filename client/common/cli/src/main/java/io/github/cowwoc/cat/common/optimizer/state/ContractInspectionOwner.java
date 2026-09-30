package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Owns the first logical contract inspection before a scheduler slot is claimed.
 *
 * @param schemaVersion the owner schema
 * @param campaignId the owning campaign
 * @param inspectionOrdinal the inspection number within one preparation cycle
 * @param logicalCallId the stable native call identity
 * @param preparationCycleRef the indexed preparation cycle
 * @param sourceCaptureRef the indexed initial source capture
 * @param runRequestRef the immutable native request
 */
public record ContractInspectionOwner(int schemaVersion, String campaignId, int inspectionOrdinal,
	String logicalCallId, EvidenceRef preparationCycleRef, EvidenceRef sourceCaptureRef,
	EvidenceRef runRequestRef)
{
	/**
	 * Validates the owner's campaign, source, and call references.
	 *
	 * @param schemaVersion the owner schema
	 * @param campaignId the owning campaign
	 * @param inspectionOrdinal the inspection number within one preparation cycle
	 * @param logicalCallId the stable native call identity
	 * @param preparationCycleRef the indexed preparation cycle
	 * @param sourceCaptureRef the indexed initial source capture
	 * @param runRequestRef the immutable native request
	 */
	public ContractInspectionOwner
	{
		Objects.requireNonNull(preparationCycleRef, "preparationCycleRef");
		Objects.requireNonNull(sourceCaptureRef, "sourceCaptureRef");
		Objects.requireNonNull(runRequestRef, "runRequestRef");
		if (schemaVersion != 1 || campaignId == null || campaignId.isBlank() || inspectionOrdinal < 0 ||
			logicalCallId == null || logicalCallId.isBlank() ||
			!preparationCycleRef.collection().equals("contract-preparation-cycles") ||
			!sourceCaptureRef.collection().equals("contract-source-captures") ||
			!runRequestRef.collection().equals("run-requests"))
			throw new IllegalArgumentException("Contract inspection owner identity is invalid");
	}
}
