package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Read-only current campaign scope for C4's next preparation cycle.
 *
 * @param campaignId pinned campaign identity
 * @param kind initial or proof-bound revision preparation
 * @param priorLineage active confirmed lineage for revision
 * @param quiescenceProofRef proven revision barrier evidence
 * @param preparationGeneration current generation within this scope
 * @param sourceChangeRef indexed source-change predecessor when generation advanced
 * @param transitionCounter observed campaign transition counter
 */
public record PreparationAuthority(String campaignId, Kind kind, Optional<LineageKey> priorLineage,
	Optional<EvidenceRef> quiescenceProofRef, long preparationGeneration,
	Optional<EvidenceRef> sourceChangeRef, long transitionCounter)
{
	/** Preparation scope kinds. */
	public enum Kind
	{
		INITIAL,
		REVISION
	}

	/**
	 * Validates the complete read-only authority shape.
	 *
	 * @param campaignId pinned campaign identity
	 * @param kind preparation kind
	 * @param priorLineage active prior lineage for revision
	 * @param quiescenceProofRef proven revision barrier evidence
	 * @param preparationGeneration current scope generation
	 * @param sourceChangeRef source-change predecessor
	 * @param transitionCounter observed transition counter
	 */
	public PreparationAuthority
	{
		if (campaignId == null || campaignId.isBlank() || preparationGeneration < 1 || transitionCounter < 0)
			throw new IllegalArgumentException("Preparation authority identity or generation is invalid");
		Objects.requireNonNull(kind, "kind");
		Objects.requireNonNull(priorLineage, "priorLineage");
		Objects.requireNonNull(quiescenceProofRef, "quiescenceProofRef");
		Objects.requireNonNull(sourceChangeRef, "sourceChangeRef");
		if (kind == Kind.INITIAL && (priorLineage.isPresent() || quiescenceProofRef.isPresent()) ||
			kind == Kind.REVISION && (priorLineage.isEmpty() || quiescenceProofRef.isEmpty()) ||
			(preparationGeneration == 1) != sourceChangeRef.isEmpty())
			throw new IllegalArgumentException("Preparation authority has a contradictory scope");
	}
}
