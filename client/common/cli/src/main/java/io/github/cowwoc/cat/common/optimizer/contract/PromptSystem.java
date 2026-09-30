package io.github.cowwoc.cat.common.optimizer.contract;

import io.github.cowwoc.cat.common.optimizer.candidate.DeliveryWriteAuthority;
import io.github.cowwoc.cat.common.optimizer.candidate.EditableRoot;
import java.nio.file.Path;
import java.util.List;

/**
 * The selected target, reviewed source identities, and editable file roots.
 *
 * @param targetPath relative selected target path
 * @param sources reviewed sources and their retained byte identities
 * @param editableRoots user-approved relative editing roots
 */
public record PromptSystem(Path targetPath, List<PromptSource> sources, List<EditableRoot> editableRoots)
{
	/**
	 * Validates and defensively copies the prompt-system graph.
	 */
	public PromptSystem
	{
		if (targetPath == null || targetPath.isAbsolute() || targetPath.normalize().startsWith(".."))
			throw new IllegalArgumentException("Target path is invalid");
		sources = List.copyOf(sources);
		editableRoots = new DeliveryWriteAuthority(editableRoots).roots();
	}
}
