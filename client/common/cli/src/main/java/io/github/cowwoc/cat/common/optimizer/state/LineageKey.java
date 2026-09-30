package io.github.cowwoc.cat.common.optimizer.state;

/**
 * A confirmed contract's distinct campaign lineage.
 *
 * @param lineageId distinct lineage identity
 * @param contractId confirmed contract identity
 */
public record LineageKey(String lineageId, String contractId)
{
	/**
	 * Validates the lineage and contract identities.
	 */
	public LineageKey
	{
		if (lineageId == null || lineageId.isBlank() || contractId == null || contractId.isBlank())
			throw new IllegalArgumentException("Lineage and contract ids must be non-blank");
	}
}
