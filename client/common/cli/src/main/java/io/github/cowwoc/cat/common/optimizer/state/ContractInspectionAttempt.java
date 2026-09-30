package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Retains the transport attempt attached to one claimed contract inspection call.
 *
 * @param header the common claimed-call and owner identity
 * @param requestRef the source capture inspected by this call
 * @param attemptOrdinal the transport retry number for this logical call
 */
public record ContractInspectionAttempt(ProducerAttemptHeader header, EvidenceRef requestRef,
	int attemptOrdinal)
{
	/**
	 * Validates the inspection attempt's kind, source, and retry number.
	 *
	 * @param header the common claimed-call and owner identity
	 * @param requestRef the source capture inspected by this call
	 * @param attemptOrdinal the transport retry number for this logical call
	 */
	public ContractInspectionAttempt
	{
		Objects.requireNonNull(header, "header");
		Objects.requireNonNull(requestRef, "requestRef");
		if (header.kind() != ProducerAttemptKind.INSPECTION ||
			!header.ownerRequestRef().collection().equals("contract-preparation-owners") ||
			header.preparationCycleRef().isEmpty() ||
			!requestRef.collection().equals("contract-source-captures") || attemptOrdinal < 0)
			throw new IllegalArgumentException("Contract inspection attempt identity is invalid");
	}
}
