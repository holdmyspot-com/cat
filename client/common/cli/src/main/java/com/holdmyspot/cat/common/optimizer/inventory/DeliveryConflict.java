package com.holdmyspot.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A reason that delivery stops before changing any file.
 *
 * @param kind the kind of conflict
 * @param target the candidate's overlay target that the conflict blocks
 * @param path the path whose current state causes the conflict: {@code target} itself, a path above or below it, or a
 *             symbolic link
 */
public record DeliveryConflict(Kind kind, Path target, Path path)
{
	/**
	 * Creates a conflict.
	 *
	 * @param kind the kind of conflict
	 * @param target the candidate's overlay target that the conflict blocks
	 * @param path the path whose current state causes the conflict
	 * @throws NullPointerException if any of the arguments are null
	 */
	public DeliveryConflict
	{
		requireThat(kind, "kind").isNotNull();
		requireThat(target, "target").isNotNull();
		requireThat(path, "path").isNotNull();
	}

	/**
	 * The kinds of delivery conflict.
	 */
	public enum Kind
	{
		/**
		 * The candidate changes a file that the base worktree also changed or deleted since the fork.
		 */
		BOTH_CHANGED,
		/**
		 * The candidate's file collides with the current structure: a file at a path the candidate creates, a file at a
		 * directory the candidate's path passes through, or files below a path the candidate writes as a file.
		 */
		STRUCTURAL_COLLISION,
		/**
		 * A symbolic link through which the fork reached the candidate's target was removed or retargeted.
		 */
		LINK_DESTINATION_CHANGED,
		/**
		 * The candidate's target path now passes through a symbolic link, so it resolves to a different file.
		 */
		TARGET_IDENTITY_MISMATCH
	}
}
