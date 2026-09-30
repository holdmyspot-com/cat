package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * One native callback id and its retained raw event in observed order.
 *
 * @param nativeCallId native callback identity
 * @param rawEvidenceRef exact raw callback evidence
 */
public record NativeCallEvidence(String nativeCallId, EvidenceRef rawEvidenceRef)
{
	/** Validates the native callback entry. */
	public NativeCallEvidence
	{
		if (nativeCallId == null || nativeCallId.isBlank())
			throw new IllegalArgumentException("Native call id must be non-blank");
		Objects.requireNonNull(rawEvidenceRef, "rawEvidenceRef");
	}
}
