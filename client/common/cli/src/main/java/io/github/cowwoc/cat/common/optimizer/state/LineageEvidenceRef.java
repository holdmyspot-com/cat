package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Immutable evidence bound to one confirmed lineage.
 *
 * @param lineage confirmed lineage
 * @param evidence immutable evidence
 */
public record LineageEvidenceRef(LineageKey lineage, EvidenceRef evidence)
{
	/**
	 * Requires both the lineage and evidence reference.
	 */
	public LineageEvidenceRef
	{
		Objects.requireNonNull(lineage, "lineage");
		Objects.requireNonNull(evidence, "evidence");
	}
}
