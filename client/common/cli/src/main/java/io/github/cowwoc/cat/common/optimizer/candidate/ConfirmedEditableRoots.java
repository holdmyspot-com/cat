package io.github.cowwoc.cat.common.optimizer.candidate;

import io.github.cowwoc.cat.common.optimizer.api.EvidenceRef;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * C4-confirmed editable roots bound to a pinned active project and contract.
 *
 * @param schemaVersion durable schema version
 * @param projectRoot pinned canonical active worktree root
 * @param confirmedContractRef immutable confirmed contract
 * @param confirmedSourceBaselineRef immutable confirmed known-source baseline
 * @param roots ordered confirmed edit roots
 */
public record ConfirmedEditableRoots(int schemaVersion, Path projectRoot, EvidenceRef confirmedContractRef,
	String confirmedSourceBaselineRef, List<EditableRoot> roots)
{
	/** The only supported editable root schema. */
	public static final int SCHEMA_VERSION = 2;

	/** Validates the pinned authority shape. */
	public ConfirmedEditableRoots
	{
		if (schemaVersion != SCHEMA_VERSION)
			throw new IllegalArgumentException("Unsupported confirmed editable roots schema: " + schemaVersion);
		Objects.requireNonNull(projectRoot, "projectRoot");
		if (!projectRoot.isAbsolute() || !projectRoot.equals(projectRoot.normalize()))
			throw new IllegalArgumentException("Confirmed project root must be absolute and normalized");
		Objects.requireNonNull(confirmedContractRef, "confirmedContractRef");
		if (!confirmedContractRef.collection().equals("contracts"))
			throw new IllegalArgumentException("Confirmed roots must name a contracts evidence ref");
		if (!PromptSnapshot.isSha256(confirmedSourceBaselineRef))
			throw new IllegalArgumentException("Confirmed roots require a SHA-256 source baseline ref");
		roots = new DeliveryWriteAuthority(roots).roots();
	}
}
