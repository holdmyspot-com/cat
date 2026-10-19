package com.holdmyspot.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * An operation a prompt author attempt performed that its edit authority forbids.
 *
 * @param path the path in the prompt author's copy at which the attempt performed the operation
 * @param target the path the operation changes after the fork's symbolic links are resolved, or {@code path} if they
 *               form a cycle
 * @param operation the operation
 * @param limit the reason the operation is forbidden
 */
public record ForbiddenOperation(Path path, Path target, Operation operation, Limit limit)
{
	/**
	 * Creates a forbidden operation.
	 *
	 * @param path the path in the prompt author's copy at which the attempt performed the operation
	 * @param target the path the operation changes after the fork's symbolic links are resolved, or {@code path} if
	 *               they form a cycle
	 * @param operation the operation
	 * @param limit the reason the operation is forbidden
	 * @throws NullPointerException if any of the arguments are null
	 */
	public ForbiddenOperation
	{
		requireThat(path, "path").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(operation, "operation").isNotNull();
		requireThat(limit, "limit").isNotNull();
	}
}
