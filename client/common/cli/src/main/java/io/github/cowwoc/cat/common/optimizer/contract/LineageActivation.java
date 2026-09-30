package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;

/**
 * A confirmed contract bound to its newly active C1 lineage.
 *
 * @param contract persisted confirmed contract
 * @param lineageId activated lineage identity
 * @param transitionCounter resulting campaign counter
 * @param sourceAuthorityRef confirmed source baseline and editable roots
 */
public record LineageActivation(ConfirmedContract contract, String lineageId, long transitionCounter,
	EvidenceRef sourceAuthorityRef)
{
	/**
	 * Validates a lineage-activation result.
	 */
	public LineageActivation
	{
		if (contract == null)
			throw new NullPointerException("contract");
		ContractValues.nonBlank(lineageId, "lineage id");
		if (transitionCounter < 0)
			throw new IllegalArgumentException("Transition counter is invalid");
		if (sourceAuthorityRef == null || !sourceAuthorityRef.collection().equals("confirmed-editable-roots"))
			throw new IllegalArgumentException("Source authority reference is invalid");
	}
}
