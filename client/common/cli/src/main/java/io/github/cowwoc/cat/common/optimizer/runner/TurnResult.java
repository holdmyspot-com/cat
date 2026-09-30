package io.github.cowwoc.cat.common.optimizer.runner;

import java.util.Objects;

import static io.github.cowwoc.cat.common.optimizer.runner.RunnerIdentity.ObservationRules.check;

/**
 * Immutable TurnResult observation at a native runner boundary.
 *
 * @param turnOrdinal the recorded turnOrdinal
 * @param conversationId the recorded conversationId
 * @param responseText the recorded responseText
 * @param nativeTurnEvent the recorded nativeTurnEvent
 */
public record TurnResult(int turnOrdinal,
	String conversationId,
	String responseText,
	NativeEventEvidence nativeTurnEvent)
{
    /**
     * Validates the observed fields.
     */
    public TurnResult
    {
		if (turnOrdinal < 1)
			throw new IllegalArgumentException("turnOrdinal must be positive");
		check(conversationId, "conversationId");
		Objects.requireNonNull(responseText, "responseText");
		Objects.requireNonNull(nativeTurnEvent, "nativeTurnEvent");
		if (!nativeTurnEvent.conversationId().equals(conversationId))
			throw new IllegalArgumentException("conversation mismatch");
    }
}
