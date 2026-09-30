package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Persisted generation and predecessor for the current or most recent preparation proof scope.
 *
 * @param revisionProofRef revision proof that owns this generation, absent for initial preparation
 * @param generation generation within that exact proof scope
 * @param sourceChangeRef indexed source-change predecessor, present after generation one
 */
public record PreparationScope(Optional<EvidenceRef> revisionProofRef, long generation,
	Optional<EvidenceRef> sourceChangeRef)
{
	/**
	 * Validates generation and evidence identity.
	 *
	 * @param revisionProofRef owning revision proof, if any
	 * @param generation preparation generation
	 * @param sourceChangeRef predecessor cause after the first generation
	 */
	public PreparationScope
	{
		Objects.requireNonNull(revisionProofRef, "revisionProofRef");
		Objects.requireNonNull(sourceChangeRef, "sourceChangeRef");
		if (generation < 1 || (generation == 1) != sourceChangeRef.isEmpty() ||
			revisionProofRef.isPresent() &&
				!revisionProofRef.orElseThrow().collection().equals("scheduler-quiescence-proofs") ||
			sourceChangeRef.isPresent() &&
				!sourceChangeRef.orElseThrow().collection().equals("contract-preparation-source-changes"))
			throw new IllegalArgumentException("Preparation scope generation or evidence is invalid");
	}

	/**
	 * Returns a pristine INITIAL preparation scope.
	 *
	 * @return generation-one scope
	 */
	public static PreparationScope initial()
	{
		return new PreparationScope(Optional.empty(), 1, Optional.empty());
	}
}
