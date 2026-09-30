package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Retains the transport attempt attached to one assigned and claimed trial.
 *
 * @param header original call claim and prepared owner identity
 * @param requestRef immutable trial assignment
 * @param attemptOrdinal transport retry number
 */
public record TrialTransportAttempt(ProducerAttemptHeader header, EvidenceRef requestRef,
	int attemptOrdinal)
{
	/**
	 * Validates the trial owner and assignment shape.
	 *
	 * @param header original call claim and prepared owner identity
	 * @param requestRef immutable trial assignment
	 * @param attemptOrdinal transport retry number
	 */
	public TrialTransportAttempt
	{
		Objects.requireNonNull(header, "header");
		Objects.requireNonNull(requestRef, "requestRef");
		if (header.kind() != ProducerAttemptKind.TRIAL ||
			!header.ownerRequestRef().collection().equals("prepared-lineage-calls") ||
			header.preparationCycleRef().isPresent() ||
			!requestRef.collection().equals("trial-assignments") || attemptOrdinal < 0)
			throw new IllegalArgumentException("Trial transport attempt identity is invalid");
	}
}
