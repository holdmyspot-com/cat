package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Create-once operation index retaining the complete request for exact idempotency.
 *
 * @param operation complete native request
 * @param terminalRef accepted receipt or rejection
 */
record GenerationEditOutcome(GenerationEditOperation operation, EvidenceRef terminalRef)
{
	/** Requires a complete terminal mapping. */
	GenerationEditOutcome
	{
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(terminalRef, "terminalRef");
		if (!terminalRef.collection().equals("generation-edit-receipts") &&
			!terminalRef.collection().equals("generation-edit-rejections"))
			throw new IllegalArgumentException("Generation edit outcome has invalid terminal collection");
	}
}
