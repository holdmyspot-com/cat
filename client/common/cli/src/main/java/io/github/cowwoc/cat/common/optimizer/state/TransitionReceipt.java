package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Committed transition authority embedded in the campaign record.
 *
 * @param binding exact campaign identity and directory
 * @param kind closed transition kind
 * @param expectedCounter predecessor counter
 * @param producerRecordRef immutable producer record
 * @param resultingCounter committed counter
 * @param result committed state summary and receipt identity
 */
public record TransitionReceipt(CampaignBinding binding, TransitionKind kind, long expectedCounter,
	EvidenceRef producerRecordRef, long resultingCounter, TransitionResult result)
{
	/**
	 * Requires one counter advance and a matching committed result.
	 */
	public TransitionReceipt
	{
		Objects.requireNonNull(binding, "binding");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(producerRecordRef, "producerRecordRef");
		Objects.requireNonNull(result, "result");
		if (expectedCounter < 0 || resultingCounter != expectedCounter + 1 ||
			result.resultingCounter() != resultingCounter)
			throw new IllegalArgumentException("Transition receipt counter or result is inconsistent");
	}
}
