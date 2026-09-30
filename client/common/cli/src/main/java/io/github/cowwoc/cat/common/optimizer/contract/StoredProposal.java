package io.github.cowwoc.cat.common.optimizer.contract;

/**
 * A proposal bound to its immutable evidence identity.
 *
 * @param proposal persisted proposal
 * @param evidenceId immutable evidence identity
 * @param loadingEvidenceId immutable loading evidence identity
 */
public record StoredProposal(ContractProposal proposal, String evidenceId, String loadingEvidenceId)
{
	/**
 * Validates a persisted proposal result.
 */
	public StoredProposal
	{
		if (proposal == null)
			throw new NullPointerException("proposal");
		ContractValues.nonBlank(evidenceId, "evidence id");
		ContractValues.nonBlank(loadingEvidenceId, "loading evidence id");
	}
}
