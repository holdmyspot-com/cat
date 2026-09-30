package io.github.cowwoc.cat.common.optimizer.loading;

import java.nio.file.Path;

/**
 * Binds a loading receipt source identity to its project-relative path.
 *
 * @param sourceId receipt source identity
 * @param relativePath normalized project-relative source path
 */
public record PromptSourceBinding(String sourceId, Path relativePath)
{
	/** Validates a receipt binding. */
	public PromptSourceBinding
	{
		if (sourceId == null || sourceId.isBlank() || relativePath == null || relativePath.isAbsolute() ||
			!relativePath.equals(relativePath.normalize()) || relativePath.startsWith(".."))
		{
			throw new IllegalArgumentException("Prompt source binding is invalid");
		}
	}
}
