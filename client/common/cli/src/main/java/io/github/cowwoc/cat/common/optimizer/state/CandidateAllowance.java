package io.github.cowwoc.cat.common.optimizer.state;

/**
 * A one-based formal candidate allowance reserved inside one lineage.
 *
 * @param candidateId candidate identity
 * @param candidateIndex allowance index
 */
public record CandidateAllowance(String candidateId, int candidateIndex)
{
	/** Validates the immutable allowance. */
	public CandidateAllowance
	{
		if (candidateId == null || candidateId.isBlank() || candidateIndex < 1)
			throw new IllegalArgumentException("Candidate id and index are invalid");
	}
}
