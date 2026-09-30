package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Retains one private candidate authoring attempt under its claimed native call.
 *
 * @param header original scheduler claim and prepared owner
 * @param requestRef indexed semantic generation request
 * @param attemptOrdinal transport retry number
 */
record CandidateTransportAttempt(ProducerAttemptHeader header, EvidenceRef requestRef,
	int attemptOrdinal)
{
	/**
	 * Validates the candidate attempt identity.
	 *
	 * @param header original scheduler claim and prepared owner
	 * @param requestRef indexed semantic generation request
	 * @param attemptOrdinal transport retry number
	 */
	CandidateTransportAttempt
	{
		Objects.requireNonNull(header, "header");
		Objects.requireNonNull(requestRef, "requestRef");
		if (header.kind() != ProducerAttemptKind.CANDIDATE ||
			!header.ownerRequestRef().collection().equals("prepared-lineage-calls") ||
			header.preparationCycleRef().isPresent() ||
			!requestRef.collection().equals("candidate-generation-requests") || attemptOrdinal < 0)
			throw new IllegalArgumentException("Candidate transport attempt identity is invalid");
	}
}
