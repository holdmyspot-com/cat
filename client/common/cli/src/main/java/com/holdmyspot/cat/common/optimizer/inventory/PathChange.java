package com.holdmyspot.cat.common.optimizer.inventory;

import static io.github.cowwoc.requirements13.java.DefaultJavaValidators.requireThat;

import java.nio.file.Path;

/**
 * A difference at one path between two inventories.
 */
public sealed interface PathChange
{
	/**
	 * Returns the path that differs.
	 *
	 * @return the path, relative to the project directory or absolute for an external file
	 */
	Path path();

	/**
	 * The path is recorded only in the later inventory.
	 *
	 * @param path the path
	 * @param after the later entry
	 */
	record Added(Path path, FileEntry after) implements PathChange
	{
		/**
		 * Creates an addition.
		 *
		 * @param path the path
		 * @param after the later entry
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Added
		{
			requireThat(path, "path").isNotNull();
			requireThat(after, "after").isNotNull();
		}
	}

	/**
	 * The path is recorded only in the earlier inventory.
	 *
	 * @param path the path
	 * @param before the earlier entry
	 */
	record Removed(Path path, FileEntry before) implements PathChange
	{
		/**
		 * Creates a removal.
		 *
		 * @param path the path
		 * @param before the earlier entry
		 * @throws NullPointerException if any of the arguments are null
		 */
		public Removed
		{
			requireThat(path, "path").isNotNull();
			requireThat(before, "before").isNotNull();
		}
	}

	/**
	 * The path is recorded in both inventories with different kinds, content, modes or link destinations.
	 *
	 * @param path the path
	 * @param before the earlier entry
	 * @param after the later entry
	 */
	record Modified(Path path, FileEntry before, FileEntry after) implements PathChange
	{
		/**
		 * Creates a modification.
		 *
		 * @param path the path
		 * @param before the earlier entry
		 * @param after the later entry
		 * @throws NullPointerException if any of the arguments are null
		 * @throws IllegalArgumentException if {@code before} equals {@code after}
		 */
		public Modified
		{
			requireThat(path, "path").isNotNull();
			requireThat(after, "after").isNotEqualTo(before, "before");
		}
	}
}
