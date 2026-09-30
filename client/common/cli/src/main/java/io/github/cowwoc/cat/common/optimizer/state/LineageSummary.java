package io.github.cowwoc.cat.common.optimizer.state;

import java.util.List;

/**
 * Immutable formal-candidate allowance history for one confirmed contract.
 *
 * @param lineageId lineage identity
 * @param confirmedContractId contract identity
 * @param nextCandidateIndex next allowance index
 * @param candidates immutable allowances
 */
public record LineageSummary(String lineageId, String confirmedContractId, int nextCandidateIndex,
	List<CandidateAllowance> candidates)
{
	/** Validates contiguous allowances and defensively copies them. */
	public LineageSummary
	{
		if (lineageId == null || lineageId.isBlank() || confirmedContractId == null || confirmedContractId.isBlank())
			throw new IllegalArgumentException("Lineage and confirmed contract ids must be non-blank");
		candidates = List.copyOf(candidates);
		if (nextCandidateIndex != candidates.size() + 1)
			throw new IllegalArgumentException("Next candidate index must follow immutable candidate history");
		for (int index = 0; index < candidates.size(); ++index)
			if (candidates.get(index).candidateIndex() != index + 1)
				throw new IllegalArgumentException("Candidate indices must be contiguous from one");
		if (candidates.stream().map(CandidateAllowance::candidateId).distinct().count() != candidates.size())
			throw new IllegalArgumentException("Lineage contains duplicate candidate ids");
	}
}
