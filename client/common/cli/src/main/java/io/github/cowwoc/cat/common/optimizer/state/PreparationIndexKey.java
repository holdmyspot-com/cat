package io.github.cowwoc.cat.common.optimizer.state;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.util.Objects;

/**
 * Names one preparation scope consistently across its source and cycle indexes.
 */
final class PreparationIndexKey
{
	/**
	 * Prevents construction.
	 */
	private PreparationIndexKey()
	{
	}

	/**
	 * Includes the owning proof and source-change predecessor so distinct revision scopes cannot collide.
	 *
	 * @param authority the current or retained preparation scope
	 * @return the campaign-scoped immutable index key
	 */
	static String derive(PreparationAuthority authority)
	{
		Objects.requireNonNull(authority, "authority");
		return authority.kind().name() + ":" + authority.quiescenceProofRef().map(EvidenceRef::evidenceId).
			orElse("initial") + ":" + authority.preparationGeneration() + ":" +
			authority.sourceChangeRef().map(EvidenceRef::evidenceId).orElse("first");
	}
}
