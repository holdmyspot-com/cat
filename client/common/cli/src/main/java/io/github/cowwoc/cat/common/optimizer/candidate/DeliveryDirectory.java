package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One directory in the transaction-owned creation plan.
 *
 * @param relativePath normalized project-relative directory
 * @param phase durable ownership phase
 */
public record DeliveryDirectory(Path relativePath, DeliveryDirectoryPhase phase)
{
	/** Validates a project-relative directory plan item. */
	public DeliveryDirectory
	{
		DeliveryPath.validateRelative(relativePath);
		Objects.requireNonNull(phase, "phase");
	}
}
