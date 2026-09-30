package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Inert request to validate and commit one evidence-backed lifecycle transition.
 *
 * @param binding exact campaign binding
 * @param expectedCounter observed transition counter
 * @param kind closed transition kind
 * @param producerRecordRef immutable producer record
 */
public record TransitionRequest(CampaignBinding binding, long expectedCounter, TransitionKind kind,
	EvidenceRef producerRecordRef)
{
	/**
	 * Validates the request envelope without granting mutation authority.
	 */
	public TransitionRequest
	{
		Objects.requireNonNull(binding, "binding");
		if (expectedCounter < 0)
			throw new IllegalArgumentException("Expected counter must not be negative");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(producerRecordRef, "producerRecordRef");
	}
}
