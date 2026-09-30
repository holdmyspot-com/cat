package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Provides the retained owner and typed native request to a scheduler invocation.
 *
 * @param ownerRef the indexed preparation owner
 * @param owner the validated logical inspection owner
 * @param cycle the validated preparation cycle
 * @param request the validated native run request
 */
public record PreparedInspectionCall(EvidenceRef ownerRef, ContractInspectionOwner owner,
	ContractPreparationCycle cycle,
	InspectionRunRequest request)
{
	/**
	 * Validates the loaded inspection call identity.
	 *
	 * @param ownerRef the indexed preparation owner
	 * @param owner the validated logical inspection owner
	 * @param cycle the validated preparation cycle
	 * @param request the validated native run request
	 */
	public PreparedInspectionCall
	{
		Objects.requireNonNull(ownerRef, "ownerRef");
		Objects.requireNonNull(owner, "owner");
		Objects.requireNonNull(cycle, "cycle");
		Objects.requireNonNull(request, "request");
		if (!ownerRef.collection().equals("contract-preparation-owners") ||
			!owner.campaignId().equals(request.campaignId()) ||
			!owner.campaignId().equals(cycle.campaignId()) ||
			!owner.logicalCallId().equals(request.logicalCallId()))
			throw new IllegalArgumentException("Prepared inspection identity is invalid");
	}
}
