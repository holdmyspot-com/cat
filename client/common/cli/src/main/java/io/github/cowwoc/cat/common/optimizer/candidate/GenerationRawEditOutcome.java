package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact rejected raw request whose pathname could not construct a valid edit operation.
 *
 * @param operationId call-local operation identity
 * @param kind parsed edit kind
 * @param rawSourcePath untrusted source text
 * @param rawDestinationPath untrusted destination text
 * @param reason exact rejection reason
 * @param nativeToolCallRef registered callback
 * @param rejectionRef persisted typed denial
 */
record GenerationRawEditOutcome(String operationId, GenerationEditOperation.Kind kind, String rawSourcePath,
	Optional<String> rawDestinationPath, String reason, EvidenceRef nativeToolCallRef,
	EvidenceRef rejectionRef)
{
	/** Requires a complete raw request and its terminal denial. */
	GenerationRawEditOutcome
	{
		if (operationId == null || operationId.isBlank() || reason == null || reason.isBlank())
			throw new IllegalArgumentException("Raw generation operation identity is invalid");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(rawSourcePath, "rawSourcePath");
		Objects.requireNonNull(rawDestinationPath, "rawDestinationPath");
		Objects.requireNonNull(nativeToolCallRef, "nativeToolCallRef");
		Objects.requireNonNull(rejectionRef, "rejectionRef");
		if (!rejectionRef.collection().equals("generation-edit-rejections"))
			throw new IllegalArgumentException("Raw edit denial has invalid collection");
	}
}
