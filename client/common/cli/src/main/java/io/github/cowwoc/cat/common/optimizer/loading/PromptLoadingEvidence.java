package io.github.cowwoc.cat.common.optimizer.loading;

import java.nio.file.Path;
import java.util.List;

/**
 * Captured production prompt loading with path bindings.
 *
 * @param targetPath selected target path
 * @param context loaded prompt and ordered receipts
 * @param bindings receipt-to-path bindings
 */
public record PromptLoadingEvidence(Path targetPath, LoadedPromptContext context, List<PromptSourceBinding> bindings)
{
	/** Validates and copies the captured evidence. */
	public PromptLoadingEvidence
	{
		if (targetPath == null || targetPath.isAbsolute() || !targetPath.equals(targetPath.normalize()) ||
			targetPath.startsWith("..") || context == null)
			throw new IllegalArgumentException("Prompt loading evidence is invalid");
		bindings = List.copyOf(bindings);
	}
}
