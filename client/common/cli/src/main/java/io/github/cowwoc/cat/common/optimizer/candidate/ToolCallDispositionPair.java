package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * One native tool call and its C3 terminal or unresolved disposition.
 *
 * @param toolCallRef retained tool-call evidence
 * @param dispositionRef C3 disposition evidence
 */
public record ToolCallDispositionPair(EvidenceRef toolCallRef, EvidenceRef dispositionRef)
{
	/** Validates exact evidence collections. */
	public ToolCallDispositionPair
	{
		Objects.requireNonNull(toolCallRef, "toolCallRef");
		Objects.requireNonNull(dispositionRef, "dispositionRef");
		if (!toolCallRef.collection().equals("generation-tool-calls") ||
			!dispositionRef.collection().equals("generation-tool-dispositions"))
			throw new IllegalArgumentException("Tool call and disposition collections are invalid");
	}
}
