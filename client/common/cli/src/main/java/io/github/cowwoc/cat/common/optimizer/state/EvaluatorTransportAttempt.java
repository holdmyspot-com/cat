package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Retains the transport attempt attached to one claimed semantic evaluator.
 *
 * @param header original call claim and prepared owner identity
 * @param requestRef immutable semantic evaluator request
 * @param attemptOrdinal transport retry number
 */
public record EvaluatorTransportAttempt(ProducerAttemptHeader header, EvidenceRef requestRef,
	int attemptOrdinal)
{
	/**
	 * Validates evaluator attempt identity.
	 *
	 * @param header original call claim and prepared owner identity
	 * @param requestRef immutable semantic evaluator request
	 * @param attemptOrdinal transport retry number
	 */
	public EvaluatorTransportAttempt
	{
		Objects.requireNonNull(header, "header");
		Objects.requireNonNull(requestRef, "requestRef");
		if (header.kind() != ProducerAttemptKind.EVALUATOR ||
			!header.ownerRequestRef().collection().equals("prepared-lineage-calls") ||
			header.preparationCycleRef().isPresent() ||
			!requestRef.collection().equals("evaluator-requests") || attemptOrdinal < 0)
			throw new IllegalArgumentException("Evaluator transport attempt identity is invalid");
	}
}
