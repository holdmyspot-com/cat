package io.github.cowwoc.cat.common.optimizer.candidate;

import java.nio.file.Path;
import java.util.Objects;

/**
 * One exact project-relative gateway endpoint and its inspected state.
 *
 * @param path project-relative endpoint
 * @param state exact regular hash or absence
 */
public record GenerationPathState(Path path, GenerationExpectedState state)
{
	/** Rejects invalid persisted endpoint shape. */
	public GenerationPathState
	{
		DeliveryPath.validateRelative(path);
		Objects.requireNonNull(state, "state");
	}
}
