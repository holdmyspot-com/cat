package io.github.cowwoc.cat.common.optimizer.contract;

import java.nio.file.Path;

/**
 * A source location retained for an extracted requirement.
 *
 * @param path relative source path
 * @param location source-local position
 */
public record SourceLocation(Path path, String location)
{
	/**
 * Validates a source location.
 */
	public SourceLocation
	{
		if (path == null || path.isAbsolute() || path.normalize().startsWith(".."))
			throw new IllegalArgumentException("Source path is invalid");
		ContractValues.nonBlank(location, "source location");
	}
}
