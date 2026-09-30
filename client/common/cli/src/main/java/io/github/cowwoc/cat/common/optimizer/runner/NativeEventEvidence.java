package io.github.cowwoc.cat.common.optimizer.runner;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.check;

/**
 * Indexes retained native evidence within one conversation.
 * The ordinal does not claim when a host loaded or consumed a source.
 *
 * @param conversationId the recorded conversationId
 * @param eventOrdinal the recorded eventOrdinal
 * @param evidence the recorded evidence
 */
public record NativeEventEvidence(String conversationId,
	long eventOrdinal,
	EvidenceRef evidence)
{
    /**
     * Validates the observed fields.
     */
    public NativeEventEvidence
    {
		check(conversationId, "conversationId");
		if (eventOrdinal < 1)
			throw new IllegalArgumentException("eventOrdinal must be positive");
		Objects.requireNonNull(evidence, "evidence");
    }
}
