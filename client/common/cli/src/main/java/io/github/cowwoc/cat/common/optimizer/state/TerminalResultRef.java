package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Retains one immutable certified or plateau terminal result in campaign history.
 *
 * @param kind certified result or unresolved-category plateau
 * @param lineageId lineage that produced the result
 * @param candidateId frozen candidate for certification, if any
 * @param terminalRootRef immutable terminal result root
 * @param measurementResultRef recapture result required for certification
 * @param resultingCounter campaign counter that committed the result
 */
public record TerminalResultRef(Kind kind, String lineageId, Optional<String> candidateId,
	EvidenceRef terminalRootRef, Optional<EvidenceRef> measurementResultRef, long resultingCounter)
{
	/**
	 * Distinguishes the two terminal outcomes.
	 */
	public enum Kind
	{
		CERTIFIED,
		PLATEAU
	}

	/**
	 * Validates the result identity and its required evidence.
	 *
	 * @param kind certified result or unresolved-category plateau
	 * @param lineageId lineage that produced the result
	 * @param candidateId frozen candidate for certification, if any
	 * @param terminalRootRef immutable terminal result root
	 * @param measurementResultRef recapture result required for certification
	 * @param resultingCounter campaign counter that committed the result
	 */
	public TerminalResultRef
	{
		Objects.requireNonNull(kind, "kind");
		if (lineageId == null || lineageId.isBlank())
			throw new IllegalArgumentException("Terminal result lineage must be non-blank");
		Objects.requireNonNull(candidateId, "candidateId");
		Objects.requireNonNull(terminalRootRef, "terminalRootRef");
		Objects.requireNonNull(measurementResultRef, "measurementResultRef");
		if (!terminalRootRef.collection().equals("terminal-results"))
			throw new IllegalArgumentException("Terminal root has the wrong collection: " + terminalRootRef);
		for (String candidate : candidateId.stream().toList())
			if (!candidate.matches("[0-9a-f]{64}"))
				throw new IllegalArgumentException("Terminal candidate must be a lowercase SHA-256 id");
		for (EvidenceRef result : measurementResultRef.stream().toList())
			if (!result.collection().equals("measurement-results"))
				throw new IllegalArgumentException("Measurement result has the wrong collection: " + result);
		if (kind == Kind.CERTIFIED && (candidateId.isEmpty() || measurementResultRef.isEmpty()) ||
			kind == Kind.PLATEAU && measurementResultRef.isPresent())
		{
			throw new IllegalArgumentException("Terminal result kind and evidence are inconsistent");
		}
		if (resultingCounter < 1)
			throw new IllegalArgumentException("Terminal result counter must be positive");
	}
}
