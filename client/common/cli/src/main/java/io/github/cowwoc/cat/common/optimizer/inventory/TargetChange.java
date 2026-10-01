package io.github.cowwoc.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import io.github.cowwoc.cat.common.optimizer.inventory.FileEntry.RegularFile;

import java.nio.file.Path;

/**
 * A permitted change that an author attempt makes to one resolved target, which the attempt's caller adds to the
 * starting candidate's {@link Overlay}.
 */
public sealed interface TargetChange
{
	/**
	 * Returns the changed path after symbolic links are resolved.
	 *
	 * @return the path, relative to the project directory or absolute for an external target
	 */
	Path target();

	/**
	 * The target becomes a regular file. The caller reads its content from {@code source} in the author's copy, checks
	 * it against {@code after}, and calls {@link Overlay#write}.
	 *
	 * @param target the resolved target
	 * @param source the path in the author's copy that holds the new content; when several paths reach the target with
	 *               the same content, the first in path order
	 * @param after the new file's content hash and mode
	 */
	record TargetWrite(Path target, Path source, RegularFile after) implements TargetChange
	{
		/**
		 * Creates a write.
		 *
		 * @param target the resolved target
		 * @param source the path in the author's copy that holds the new content
		 * @param after the new file's content hash and mode
		 * @throws NullPointerException if any of the arguments are null
		 */
		public TargetWrite
		{
			requireThat(target, "target").isNotNull();
			requireThat(source, "source").isNotNull();
			requireThat(after, "after").isNotNull();
		}
	}

	/**
	 * The target is deleted. The caller calls {@link Overlay#delete}.
	 *
	 * @param target the resolved target
	 */
	record TargetDelete(Path target) implements TargetChange
	{
		/**
		 * Creates a delete.
		 *
		 * @param target the resolved target
		 * @throws NullPointerException if {@code target} is null
		 */
		public TargetDelete
		{
			requireThat(target, "target").isNotNull();
		}
	}
}
