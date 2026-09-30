package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Binds the current contract preparation authority to its indexed initial source capture.
 *
 * @param schemaVersion the cycle schema
 * @param campaignId the campaign being prepared
 * @param authority the scope that owns this cycle
 * @param sourceCaptureRef the initial target and Git inventory
 */
public record ContractPreparationCycle(int schemaVersion, String campaignId, PreparationAuthority authority,
	EvidenceRef sourceCaptureRef)
{
	/**
	 * Validates the cycle's source and campaign identity.
	 *
	 * @param schemaVersion the cycle schema
	 * @param campaignId the campaign being prepared
	 * @param authority the scope that owns this cycle
	 * @param sourceCaptureRef the initial target and Git inventory
	 */
	public ContractPreparationCycle
	{
		Objects.requireNonNull(authority, "authority");
		Objects.requireNonNull(sourceCaptureRef, "sourceCaptureRef");
		if (schemaVersion != 1 || campaignId == null || campaignId.isBlank() ||
			!campaignId.equals(authority.campaignId()) ||
			authority.kind() != PreparationAuthority.Kind.INITIAL ||
			!sourceCaptureRef.collection().equals("contract-source-captures"))
			throw new IllegalArgumentException("Contract preparation cycle identity is invalid");
	}
}
